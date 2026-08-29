package com.example.chat.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.List;

/**
 * 把 rerank 接入 Spring AI 模块化 RAG 的检索后处理（post-retrieval）扩展点：
 * 在向量"粗召回"之后、拼进 prompt 之前，用 {@link DashScopeRerankClient} 精排并截取前 {@code topN} 条。
 *
 * <p>每次请求按最终 topK 现场构造，故 {@code topN} 由构造参数传入而非配置写死。
 */
public class RerankDocumentPostProcessor implements DocumentPostProcessor {

    private final DashScopeRerankClient rerankClient;
    private final int topN;

    public RerankDocumentPostProcessor(DashScopeRerankClient rerankClient, int topN) {
        this.rerankClient = rerankClient;
        this.topN = topN;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        return rerankClient.rerank(query.text(), documents, topN);
    }
}
