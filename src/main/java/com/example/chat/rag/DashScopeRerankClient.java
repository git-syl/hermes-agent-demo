package com.example.chat.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用 DashScope（阿里云百炼）{@code /v1/reranks} 接口对候选文档做重排。
 *
 * <p>向量检索是"粗筛"，rerank 用交叉编码模型把问题与每个候选块一起打分，得到更准的相关性排序。
 * 这里把得分写回文档的 {@code rerank_score} 元数据，并按分数降序、截取前 {@code topN} 返回。
 *
 * <p>仅当 {@code chat.rag.rerank.enabled=true} 时注册为 Bean；rerank 是检索质量增强而非核心链路，
 * 远程调用失败时不抛断整个问答，而是优雅回退到向量检索的原始顺序（见 {@link #rerank}）。
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/text-rerank-api">DashScope Rerank API</a>
 */
@Component
@ConditionalOnProperty(prefix = "chat.rag.rerank", name = "enabled", havingValue = "true")
public class DashScopeRerankClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRerankClient.class);

    // 用 cl100k 估算 token（与切分器一致）。DashScope 实际分词器不同，故按上限留有余量、偏保守即可。
    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private final RestClient restClient;
    private final RagProperties.Rerank props;

    public DashScopeRerankClient(RagProperties ragProperties, RestClient.Builder restClientBuilder) {
        this.props = ragProperties.getRerank();
        if (!StringUtils.hasText(props.getApiKey())) {
            throw new IllegalStateException("已开启 rerank（chat.rag.rerank.enabled=true）但未配置 chat.rag.rerank.api-key");
        }
        this.restClient = restClientBuilder
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 对候选文档按与 {@code query} 的相关性重排，返回前 {@code topN} 条（已写入 {@code rerank_score}）。
     * 入参为空或调用失败时，回退为原始顺序的前 {@code topN} 条。
     */
    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (documents == null || documents.isEmpty() || !StringUtils.hasText(query)) {
            return documents == null ? List.of() : documents;
        }
        int n = Math.min(topN, documents.size());

        // 在 API 限额内挑选实际送审的候选：向量结果已按相关性降序，超限时丢弃靠后的低分候选；
        // 单条超长则按 token 截断（截断只用于打分，返回的仍是完整原文，索引对齐不受影响）。
        List<String> texts = new ArrayList<>();
        int budget = props.getMaxTokensPerRequest() - tokenCount(query) - tokenCount(props.getInstruct());
        for (Document doc : documents) {
            if (texts.size() >= props.getMaxDocuments()) {
                break;
            }
            String text = truncateToTokens(doc.getText(), props.getMaxTokensPerDoc());
            int cost = tokenCount(text);
            if (!texts.isEmpty() && budget - cost < 0) {
                break; // 至少送 1 条，避免极端长 query 把预算占满导致一条都发不出
            }
            budget -= cost;
            texts.add(text);
        }
        if (texts.size() < documents.size()) {
            log.warn("候选超出 rerank API 限额，仅对前 {}/{} 条精排", texts.size(), documents.size());
        }
        int sentTopN = Math.min(topN, texts.size());

        try {
            RerankResponse resp = restClient.post()
                    .uri("/v1/reranks")
                    .body(new RerankRequest(props.getModel(), texts, query, sentTopN, props.getInstruct()))
                    .retrieve()
                    .body(RerankResponse.class);

            if (resp == null || resp.results() == null || resp.results().isEmpty()) {
                log.warn("rerank 返回为空，回退向量检索原始顺序");
                return documents.subList(0, n);
            }

            List<Document> ranked = new ArrayList<>(n);
            for (RerankResponse.Result r : resp.results()) {
                if (r.index() < 0 || r.index() >= documents.size()) {
                    continue; // 防御越界的异常返回
                }
                // rerank 分作为最终排序分写入 score；原始向量分仍保留在 metadata.distance。
                ranked.add(RagDocuments.withRankScore(documents.get(r.index()), r.relevanceScore(), "rerank_score"));
            }
            return ranked;
        }
        catch (Exception e) {
            // rerank 只是质量增强，远程异常不应拖垮整个检索/问答，回退原始顺序即可。
            log.warn("rerank 调用失败，回退向量检索原始顺序：{}", e.getMessage());
            return documents.subList(0, n);
        }
    }

    /** 把文本截断到不超过 {@code maxTokens} 个 token（用于满足单条输入上限）；未超长则原样返回。 */
    private String truncateToTokens(String text, int maxTokens) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        var result = encoding.encode(text, maxTokens);
        return result.isTruncated() ? encoding.decode(result.getTokens()) : text;
    }

    private int tokenCount(String text) {
        return StringUtils.hasText(text) ? encoding.countTokens(text) : 0;
    }

    /** DashScope rerank 请求体。{@code top_n} 走蛇形命名，需显式映射。 */
    private record RerankRequest(String model, List<String> documents, String query,
                                 @JsonProperty("top_n") int topN, String instruct) {
    }

    /** DashScope rerank 响应体，只取需要的 results。 */
    private record RerankResponse(List<Result> results) {
        record Result(int index, @JsonProperty("relevance_score") double relevanceScore) {
        }
    }
}
