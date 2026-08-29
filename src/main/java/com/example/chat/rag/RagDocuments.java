package com.example.chat.rag;

import org.springframework.ai.document.Document;

/** RAG 文档小工具。 */
final class RagDocuments {

    private RagDocuments() {
    }

    /**
     * 复制文档并把"当前阶段的最终排序分"写入 {@link Document#getScore()}，同时在 metadata 留一份同名明细便于排查。
     *
     * <p>Spring AI 的 {@code score} 是 final、没有 setter，重排/融合要改分只能重建文档。原始向量分等明细仍保留在
     * metadata（如 pgvector 写入的 {@code distance}），不会被覆盖。
     *
     * @param detailScoreKey metadata 里保留该分的 key（如 {@code rrf_score}、{@code rerank_score}）
     */
    static Document withRankScore(Document doc, double score, String detailScoreKey) {
        Document.Builder builder = Document.builder()
                .id(doc.getId())
                .metadata(doc.getMetadata())
                .score(score);
        // Document 要求 text / media 恰好二选一；这里按原文档类型复制，兼容未来的多模态块。
        if (doc.isText()) {
            builder.text(doc.getText());
        }
        else {
            builder.media(doc.getMedia());
        }
        Document ranked = builder.build();
        ranked.getMetadata().put(detailScoreKey, score);
        return ranked;
    }
}
