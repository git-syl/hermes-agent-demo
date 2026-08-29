package com.example.chat.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 知识库参数，绑定 {@code chat.rag.*}。
 *
 * <p>向量库/embedding 的连接参数由 Spring AI 自身的 {@code spring.ai.*} 管理，这里只放
 * 业务层可调的检索与问答参数，避免散落在代码里写死。
 */
@ConfigurationProperties(prefix = "chat.rag")
public class RagProperties {

    /**
     * RAG 问答使用的对话模型名。交给 {@link com.example.chat.config.ModelRouter} 路由到对应 provider；
     * 默认 deepseek-v4-flash（项目里已配有可用 key），换模型只改这里即可。
     */
    private String chatModel = "deepseek-v4-flash";

    /** 向量检索相似度阈值（0~1），低于该分数的文档不召回。 */
    private double similarityThreshold = 0.5d;

    /** 检索返回的最相关文档条数。 */
    private int topK = 4;

    /** 文本切分目标 token 数：过大召回噪声多、过小语义割裂，按文档类型权衡。 */
    private int chunkTokens = 800;

    /** 相邻切块的重叠 token 数（建议 10~20% 的 chunkTokens），跨块保留上下文以改善边界召回。必须小于 chunkTokens。 */
    private int chunkOverlap = 120;

    /** 切分策略：token（官方）/ recursive（递归，默认）/ semantic（语义）。 */
    private SplitterType splitter = SplitterType.RECURSIVE;

    /**
     * 语义切分的断点分位数（0~100）。取所有相邻句子距离的该分位作为阈值，距离超过它即认为话题切换、在此断开。
     * 越大断点越少、块越大；常用 90~95。仅 {@code splitter=semantic} 时生效。
     */
    private double semanticBreakpointPercentile = 95;

    /** 重排（rerank）相关配置，绑定 {@code chat.rag.rerank.*}。 */
    private final Rerank rerank = new Rerank();

    /** 混合检索（向量 + 关键词）相关配置，绑定 {@code chat.rag.hybrid.*}。 */
    private final Hybrid hybrid = new Hybrid();

    /** embedding 批处理相关配置，绑定 {@code chat.rag.embedding.*}。 */
    private final Embedding embedding = new Embedding();

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }

    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public int getChunkTokens() { return chunkTokens; }
    public void setChunkTokens(int chunkTokens) { this.chunkTokens = chunkTokens; }

    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }

    public SplitterType getSplitter() { return splitter; }
    public void setSplitter(SplitterType splitter) { this.splitter = splitter; }

    public double getSemanticBreakpointPercentile() { return semanticBreakpointPercentile; }
    public void setSemanticBreakpointPercentile(double semanticBreakpointPercentile) {
        this.semanticBreakpointPercentile = semanticBreakpointPercentile;
    }

    public Rerank getRerank() { return rerank; }

    public Hybrid getHybrid() { return hybrid; }

    public Embedding getEmbedding() { return embedding; }

    /**
     * 重排配置：向量召回是"粗筛"（embedding 余弦相似度），rerank 用更强的交叉编码模型对
     * 候选块和问题做精排，把最相关的顶到前面，能明显改善 RAG 上下文质量。
     *
     * <p>开关默认关闭：开启后入库不受影响，仅在检索/问答时多一次 rerank API 调用。
     */
    public static class Rerank {

        /** 是否启用 rerank。开启需提供可用的 rerank 端点与 api-key。 */
        private boolean enabled = false;

        /** rerank 端点（OpenAI 兼容）。默认对接阿里云百炼 DashScope。 */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-api";

        /** rerank 服务的 API Key；建议用环境变量注入，勿写死在仓库。 */
        private String apiKey = "";

        /** rerank 模型名。 */
        private String model = "qwen3-rerank";

        /** 任务指令，提示模型按什么标准排序（DashScope 的 instruct 字段，可空）。 */
        private String instruct = "Given a web search query, retrieve relevant passages that answer the query.";

        /**
         * 粗召回候选数：送进 rerank 的文档条数。先用向量检索召回这么多，再 rerank 精排截到请求的 topK。
         * 越大召回越全但 rerank 越慢/越贵，一般取最终 topK 的 3~5 倍。
         */
        private int candidateTopK = 20;

        /** rerank 单次请求的最大文档数（默认按 qwen3-rerank：500）。超出按相关性丢弃靠后候选。 */
        private int maxDocuments = 500;

        /** rerank 单条文档的最大 token 数（默认按 qwen3-rerank：8000）。超长按 token 截断后再送（只影响打分）。 */
        private int maxTokensPerDoc = 8000;

        /** rerank 单次请求的总输入 token 上限（默认按 qwen3-rerank：120000）。超预算时丢弃靠后候选。 */
        private int maxTokensPerRequest = 120000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getInstruct() { return instruct; }
        public void setInstruct(String instruct) { this.instruct = instruct; }

        public int getCandidateTopK() { return candidateTopK; }
        public void setCandidateTopK(int candidateTopK) { this.candidateTopK = candidateTopK; }

        public int getMaxDocuments() { return maxDocuments; }
        public void setMaxDocuments(int maxDocuments) { this.maxDocuments = maxDocuments; }

        public int getMaxTokensPerDoc() { return maxTokensPerDoc; }
        public void setMaxTokensPerDoc(int maxTokensPerDoc) { this.maxTokensPerDoc = maxTokensPerDoc; }

        public int getMaxTokensPerRequest() { return maxTokensPerRequest; }
        public void setMaxTokensPerRequest(int maxTokensPerRequest) { this.maxTokensPerRequest = maxTokensPerRequest; }
    }

    /**
     * 混合检索配置：向量检索擅长语义近似，关键词（全文）检索擅长精确命中专有名词/英文/数字，
     * 两路各自召回后用<b>加权 RRF（Reciprocal Rank Fusion）</b>融合排序——行业通用、免分数归一化、
     * 对两侧量纲不敏感。开关默认关闭；开启后入库不受影响，仅检索时多一路 SQL 全文查询。
     *
     * <p>关键词腿用 PostgreSQL 内置全文检索（无需额外扩展）。中文分词较弱时建议安装 zhparser/pg_jieba
     * 并把 {@code ts-config} 指向对应配置，同时让全文索引使用相同配置（见库初始化脚本注释）。
     */
    public static class Hybrid {

        /** 是否启用混合检索。 */
        private boolean enabled = false;

        /** PostgreSQL 全文检索配置名。默认 {@code simple}（仅小写+按空白/标点切分，跨语言通用）。 */
        private String tsConfig = "simple";

        /** RRF 平滑常数 k：越大则排名靠后的文档影响越平缓，业界常用 60。 */
        private int rrfK = 60;

        /** 向量腿在融合中的权重。 */
        private double vectorWeight = 1.0d;

        /** 关键词腿在融合中的权重。 */
        private double keywordWeight = 1.0d;

        /** 每一路（向量/关键词）召回并送入融合的候选数。 */
        private int candidateTopK = 20;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getTsConfig() { return tsConfig; }
        public void setTsConfig(String tsConfig) { this.tsConfig = tsConfig; }

        public int getRrfK() { return rrfK; }
        public void setRrfK(int rrfK) { this.rrfK = rrfK; }

        public double getVectorWeight() { return vectorWeight; }
        public void setVectorWeight(double vectorWeight) { this.vectorWeight = vectorWeight; }

        public double getKeywordWeight() { return keywordWeight; }
        public void setKeywordWeight(double keywordWeight) { this.keywordWeight = keywordWeight; }

        public int getCandidateTopK() { return candidateTopK; }
        public void setCandidateTopK(int candidateTopK) { this.candidateTopK = candidateTopK; }
    }

    /**
     * embedding 批处理限额：很多第三方 embedding 端点对"单次请求文本条数"和"单条文本 token 数"有硬上限
     * （如 DashScope text-embedding-v4：单次最多 10 条、单条最多 8192 token）。Spring AI 默认的
     * {@code TokenCountBatchingStrategy} 只按 token 总和分批、不限条数，可能超限；这里把两个维度都卡住。
     */
    public static class Embedding {

        /** 单次 embedding 请求的最大文本条数。 */
        private int batchSize = 10;

        /** 单条文本的最大 token 数，超过即判定为切分异常并 fail-fast。 */
        private int maxInputTokens = 8192;

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public int getMaxInputTokens() { return maxInputTokens; }
        public void setMaxInputTokens(int maxInputTokens) { this.maxInputTokens = maxInputTokens; }
    }

    /** 切分策略枚举。 */
    public enum SplitterType {
        /** Spring AI 官方 TokenTextSplitter（token 窗口，无 overlap）。 */
        TOKEN,
        /** 自研递归切分器（递归分隔符 + token 计长 + overlap，中文友好）。 */
        RECURSIVE,
        /** 语义切分（按句向量相似度找断点，质量高但入库需额外 embedding）。 */
        SEMANTIC
    }
}
