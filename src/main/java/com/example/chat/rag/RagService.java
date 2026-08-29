package com.example.chat.rag;

import com.example.chat.config.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * RAG 知识库服务：文档解析入库、向量语义检索、检索增强问答。
 *
 * <p>问答走 Spring AI 的<b>模块化 RAG</b>（{@link RetrievalAugmentationAdvisor}），每次请求按入参动态
 * 组装检索管线，支持调相似度阈值、topK、查询改写条数、空上下文策略，并按 {@code tenantId} 做租户隔离。
 *
 * @see <a href="https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html">Spring AI RAG</a>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 文档块上记录租户的元数据 key；检索/问答按它做隔离过滤。 */
    public static final String METADATA_TENANT_ID = "tenantId";

    // Markdown 结构化解析：把代码块/引用并入所在段落（而非拆成独立文档），保留段落语境。
    private static final MarkdownDocumentReaderConfig MARKDOWN_CONFIG = MarkdownDocumentReaderConfig.builder()
            .withIncludeCodeBlock(true)
            .withIncludeBlockquote(true)
            .build();

    private final VectorStore vectorStore;
    private final RagProperties props;
    private final ChatModel chatModel;
    private final ChatClient chatClient;
    private final TextSplitter splitter;

    /** 仅当 {@code chat.rag.rerank.enabled=true} 时存在；为 null 表示未启用重排。 */
    private final @Nullable DashScopeRerankClient rerankClient;

    /** 仅当 {@code chat.rag.hybrid.enabled=true} 时存在；为 null 表示未启用混合检索（纯向量）。 */
    private final @Nullable KeywordSearchService keywordSearchService;

    public RagService(VectorStore vectorStore, ChatMemory chatMemory,
                      ModelRouter modelRouter, EmbeddingModel embeddingModel, RagProperties props,
                      @Nullable DashScopeRerankClient rerankClient,
                      @Nullable KeywordSearchService keywordSearchService) {
        this.vectorStore = vectorStore;
        this.props = props;
        this.chatModel = modelRouter.resolve(props.getChatModel());
        this.splitter = createSplitter(embeddingModel, props);
        this.rerankClient = rerankClient;
        this.keywordSearchService = keywordSearchService;

        // 基础对话客户端：系统人设 + 会话记忆 + 请求日志。
        // 检索增强（RetrievalAugmentationAdvisor）按请求动态拼装，不在这里固化。
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是一个知识库助手。请优先依据检索到的上下文回答用户问题，
                        上下文不足以回答时如实说明，不要凭空编造。回答使用简洁的中文。
                        """)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                .build();
    }

    /**
     * 按 {@code chat.rag.splitter} 选择切分策略：
     * <ul>
     *   <li>{@code TOKEN}：Spring AI 官方 {@link TokenTextSplitter}，token 窗口、无 overlap（补了中文标点）；</li>
     *   <li>{@code RECURSIVE}（默认）：递归分隔符层级 + token 计长 + overlap，中文友好，零额外成本；</li>
     *   <li>{@code SEMANTIC}：按句向量相似度找语义断点，召回更聚焦，但入库时要对句子调 embedding，更慢更贵。</li>
     * </ul>
     */
    private static TextSplitter createSplitter(EmbeddingModel embeddingModel, RagProperties props) {
        return switch (props.getSplitter()) {
            case TOKEN -> TokenTextSplitter.builder()
                    .withChunkSize(props.getChunkTokens())
                    .withPunctuationMarks(List.of('。', '！', '？', '；', '…', '\n', '.', '!', '?', ';'))
                    .build();
            case RECURSIVE -> new RecursiveTokenTextSplitter(props.getChunkTokens(), props.getChunkOverlap());
            case SEMANTIC -> new SemanticTextSplitter(embeddingModel,
                    props.getChunkTokens(), props.getChunkOverlap(), props.getSemanticBreakpointPercentile(),
                    props.getEmbedding().getBatchSize(), props.getEmbedding().getMaxInputTokens());
        };
    }

    /**
     * 解析上传文档并写入向量库，返回入库的切块数量。
     *
     * <p>按扩展名选解析器（见 {@link #documentReader}）：PDF 按页、Markdown 按结构、TXT 按纯文本、
     * 其余（WORD/PPT/HTML 等）交给 Tika。解析出的文档再用 {@link RecursiveTokenTextSplitter} 切成小块。
     *
     * <p>每个切块都打上 {@code tenantId}（租户隔离用）以及调用方传入的 {@code metadata}（如 category、lang，
     * 供检索时按需过滤）。{@code tenantId} 始终以独立入参为准，会覆盖 {@code metadata} 里的同名/空值。
     */
    public int parseAndSave(MultipartFile file, String tenantId, Map<String, Object> metadata) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String tid = requireTenant(tenantId);
        String filename = file.getOriginalFilename();
        DocumentReader reader = documentReader(filename, file.getResource());

        // TODO(P2 质量): 过滤过短碎块（token 数 < 阈值），避免标点/单字块占用 topK、污染上下文
        //               （参考 Spring AI TokenTextSplitter.minChunkLengthToEmbed）。
        List<Document> chunks = splitter.apply(reader.get());
        if (chunks.isEmpty()) {
            throw new IllegalStateException("文档解析后内容为空，无法入库：" + filename);
        }
        chunks.forEach(chunk -> {
            mergeMetadata(chunk, metadata);
            chunk.getMetadata().put(METADATA_TENANT_ID, tid); // tenantId 以独立入参为准
        });
        vectorStore.add(chunks);
        log.info("文档入库完成 - tenantId: {}, file: {}, chunks: {}", tid, filename, chunks.size());
        return chunks.size();
    }

    /** 把用户传入的业务元数据合并进切块；跳过保留键 {@code tenantId} 与空值。 */
    private static void mergeMetadata(Document chunk, Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        metadata.forEach((key, val) -> {
            if (val != null && !METADATA_TENANT_ID.equals(key)) {
                chunk.getMetadata().put(key, val);
            }
        });
    }

    /**
     * 语义检索，限定在指定租户内，返回命中的原始文档块。检索能力按配置自动叠加：
     * <ul>
     *   <li>默认：纯向量相似度检索；</li>
     *   <li>{@code chat.rag.hybrid.enabled=true}：向量 + 关键词全文检索，加权 RRF 融合排序；</li>
     *   <li>{@code chat.rag.rerank.enabled=true}：上面召回的候选再经 rerank 精排，截到 topK。</li>
     * </ul>
     *
     * @param filters             业务元数据过滤（如 category=redis），与 {@code tenantId} 做 AND；可为空
     * @param similarityThreshold 相似度阈值，null 用 {@code chat.rag.similarity-threshold}
     * @param topK                返回条数，null/&le;0 用 {@code chat.rag.top-k}
     */
    public List<Document> search(String query, String tenantId, Map<String, Object> filters,
                                 Double similarityThreshold, Integer topK) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        int finalTopK = topK(topK);
        List<Document> candidates = retrieveCandidates(query, tenantId, filters, similarityThreshold, finalTopK);
        return rerankClient != null ? rerankClient.rerank(query, candidates, finalTopK) : candidates;
    }

    /**
     * 检索增强问答（流式）：先按入参组装检索管线，再让模型基于命中上下文作答。
     * 按 {@code chatId} 维持多轮上下文，检索范围限定在 {@code tenantId} 内。
     *
     * @param filters                 业务元数据过滤（如 category=redis），与 {@code tenantId} 做 AND；可为空
     * @param similarityThreshold     相似度阈值，null 用配置默认值
     * @param topK                    召回条数，null/&le;0 用配置默认值
     * @param expanderNumberOfQueries 查询改写条数，&gt;0 时启用多查询扩展改善召回；0/null 关闭
     * @param allowEmptyContext       命中为空时是否仍让模型作答（false 则直接回复"无法回答"）
     */
    public Flux<ChatResponse> chat(String chatId, String userMessageContent, String tenantId,
                                   Map<String, Object> filters, Double similarityThreshold, Integer topK,
                                   Integer expanderNumberOfQueries, Boolean allowEmptyContext) {
        if (!StringUtils.hasText(userMessageContent)) {
            throw new IllegalArgumentException("提问内容不能为空");
        }
        String conversationId = StringUtils.hasText(chatId) ? chatId : "default";
        RetrievalAugmentationAdvisor ragAdvisor = retrievalAugmentationAdvisor(
                tenantId, filters, similarityThreshold, topK, expanderNumberOfQueries, allowEmptyContext);

        return chatClient.prompt()
                .user(userMessageContent)
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .advisors(ragAdvisor)
                .stream()
                .chatResponse();
    }

    /**
     * 按入参组装一条模块化 RAG 检索管线：
     * <ul>
     *   <li>文档召回：默认 {@link VectorStoreDocumentRetriever}（纯向量）；启用混合检索时换成
     *       "向量 + 关键词 + 加权 RRF 融合"的自定义 {@link DocumentRetriever}；</li>
     *   <li>{@link RerankDocumentPostProcessor}（可选）：检索后用 rerank 精排，把最相关的块顶到前面；</li>
     *   <li>{@link ContextualQueryAugmenter}：把命中上下文拼进 prompt，并控制空上下文行为；</li>
     *   <li>{@link MultiQueryExpander}（可选）：把原始问题改写成多条子查询，提升召回覆盖面。</li>
     * </ul>
     */
    private RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            String tenantId, Map<String, Object> filters, Double similarityThreshold, Integer topK,
            Integer expanderNumberOfQueries, Boolean allowEmptyContext) {

        int finalTopK = topK(topK);
        DocumentRetriever retriever;
        if (keywordSearchService != null) {
            // 混合检索：每条（子）查询都走"向量 + 关键词 + RRF 融合"，返回供后续环节使用的候选。
            retriever = query -> retrieveCandidates(query.text(), tenantId, filters, similarityThreshold, finalTopK);
        }
        else {
            retriever = VectorStoreDocumentRetriever.builder()
                    .vectorStore(vectorStore)
                    .similarityThreshold(threshold(similarityThreshold))
                    .topK(retrieveTopK(finalTopK))
                    .filterExpression(buildFilter(tenantId, filters))
                    .build();
        }

        ContextualQueryAugmenter augmenter = ContextualQueryAugmenter.builder()
                .allowEmptyContext(allowEmptyContext == null || allowEmptyContext)
                .build();

        RetrievalAugmentationAdvisor.Builder builder = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .queryAugmenter(augmenter);

        // 启用 rerank 时，对粗召回结果精排并截到最终 topK。
        if (rerankClient != null) {
            builder.documentPostProcessors(new RerankDocumentPostProcessor(rerankClient, finalTopK));
        }

        if (expanderNumberOfQueries != null && expanderNumberOfQueries > 0) {
            // 用独立的 ChatClient.Builder（不带 RAG/记忆 advisor）跑查询改写，避免递归。
            // includeOriginal=true：保留原始问题一起检索，避免改写跑偏时丢掉原问题的字面命中。
            builder.queryExpander(MultiQueryExpander.builder()
                    .chatClientBuilder(ChatClient.builder(chatModel))
                    .numberOfQueries(expanderNumberOfQueries)
                    .includeOriginal(true)
                    .build());
        }
        return builder.build();
    }

    private double threshold(Double similarityThreshold) {
        return similarityThreshold != null ? similarityThreshold : props.getSimilarityThreshold();
    }

    private int topK(Integer topK) {
        return (topK != null && topK > 0) ? topK : props.getTopK();
    }

    /**
     * 实际召回（返回给 rerank/问答前）的条数：启用 rerank 时多召回候选（至少不少于最终 topK），
     * 供 rerank 精排后再截到 finalTopK；未启用则直接召回 finalTopK。
     */
    private int retrieveTopK(int finalTopK) {
        if (rerankClient == null) {
            return finalTopK;
        }
        return Math.max(props.getRerank().getCandidateTopK(), finalTopK);
    }

    /**
     * 召回候选文档：混合检索开启时走"向量 + 关键词 + 加权 RRF 融合"，否则纯向量。
     * 返回 {@link #retrieveTopK(int)} 条（启用 rerank 时多召回，供后续精排）。
     */
    private List<Document> retrieveCandidates(String query, String tenantId, Map<String, Object> filters,
                                              Double similarityThreshold, int finalTopK) {
        int outK = retrieveTopK(finalTopK);
        if (keywordSearchService == null) {
            return vectorSearch(query, tenantId, filters, similarityThreshold, outK);
        }
        RagProperties.Hybrid h = props.getHybrid();
        int legK = Math.max(h.getCandidateTopK(), outK);
        List<Document> vectorHits = vectorSearch(query, tenantId, filters, similarityThreshold, legK);
        List<Document> keywordHits = keywordSearchService.search(query, tenantId, filters, legK);
        return RankFusion.fuse(vectorHits, keywordHits,
                h.getVectorWeight(), h.getKeywordWeight(), h.getRrfK(), outK);
    }

    /** 纯向量相似度检索，限定在指定租户内，并叠加业务元数据过滤。 */
    private List<Document> vectorSearch(String query, String tenantId, Map<String, Object> filters,
                                        Double similarityThreshold, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .similarityThreshold(threshold(similarityThreshold))
                .topK(topK)
                .filterExpression(buildFilter(tenantId, filters))
                .build());
    }

    /**
     * 按文件扩展名挑选最合适的解析器：
     * <ul>
     *   <li>{@code pdf} → {@link PagePdfDocumentReader}（按页）；</li>
     *   <li>{@code md/markdown} → {@link MarkdownDocumentReader}（按标题/段落结构化，召回质量更好）；</li>
     *   <li>{@code txt/text} → {@link TextReader}（纯文本，整篇读入后由切分器切块）；</li>
     *   <li>其它（doc/docx/ppt/html/rtf…）→ {@link TikaDocumentReader}（自动识别）。</li>
     * </ul>
     */
    private static DocumentReader documentReader(String filename, Resource resource) {
        return switch (extensionOf(filename)) {
            case "pdf" -> new PagePdfDocumentReader(resource);
            case "md", "markdown" -> new MarkdownDocumentReader(resource, MARKDOWN_CONFIG);
            case "txt", "text" -> new TextReader(resource);
            default -> new TikaDocumentReader(resource);
        };
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    /** 校验租户 ID 非空后返回；用于元数据写入。 */
    private static String requireTenant(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        return tenantId;
    }

    /**
     * 构造检索过滤表达式：{@code tenantId}（必传，租户硬隔离）与各 {@code filters} 项按 AND 组合。
     * 程序化构建，值作为字面量绑定，无注入风险；{@code filters} 里的 {@code tenantId} 与空值会被忽略。
     */
    private static Filter.Expression buildFilter(String tenantId, Map<String, Object> filters) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op expr = b.eq(METADATA_TENANT_ID, requireTenant(tenantId));
        if (filters != null) {
            for (Map.Entry<String, Object> e : filters.entrySet()) {
                if (e.getValue() != null && !METADATA_TENANT_ID.equals(e.getKey())) {
                    expr = b.and(expr, b.eq(e.getKey(), e.getValue()));
                }
            }
        }
        return expr.build();
    }
}
