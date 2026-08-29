package com.example.chat.rag;

import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加权 RRF（Reciprocal Rank Fusion，倒数排名融合）：把多路检索结果按"排名"而非"分数"融合。
 *
 * <p>每路里某文档排第 {@code rank}（从 1 起），贡献 {@code weight * 1/(k + rank)}；同一文档在各路的贡献累加，
 * 按总分降序得到最终排序。相比把余弦相似度与 ts_rank 等不同量纲的分数做加权求和，RRF <b>无需归一化</b>、
 * 对异常分数稳健，是搜索/向量库混合检索的通用做法。
 *
 * @see <a href="https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf">Cormack et al., 2009</a>
 */
final class RankFusion {

    private RankFusion() {
    }

    /**
     * 对若干已排序的文档列表做加权 RRF 融合，返回前 {@code topN} 条（融合分写入 {@code rrf_score} 元数据）。
     * 文档以 {@link Document#getId()} 去重；保留首次出现的 Document 实例（传入顺序靠前的列表优先）。
     *
     * @param rankedLists 各路检索结果，已按相关度降序
     * @param weights     与 {@code rankedLists} 一一对应的权重
     * @param k           RRF 平滑常数（业界常用 60）
     * @param topN        返回条数
     */
    static List<Document> weightedReciprocalRankFusion(List<List<Document>> rankedLists,
                                                       List<Double> weights, int k, int topN) {
        if (rankedLists.size() != weights.size()) {
            throw new IllegalArgumentException("rankedLists 与 weights 数量必须一致");
        }
        Map<String, Document> byId = new LinkedHashMap<>();
        Map<String, Double> scoreById = new LinkedHashMap<>();

        for (int i = 0; i < rankedLists.size(); i++) {
            List<Document> list = rankedLists.get(i);
            double weight = weights.get(i);
            for (int idx = 0; idx < list.size(); idx++) {
                Document doc = list.get(idx);
                String id = doc.getId();
                byId.putIfAbsent(id, doc);
                // idx 从 0 起，排名 rank = idx + 1，故分母为 k + idx + 1。
                scoreById.merge(id, weight / (k + idx + 1.0), Double::sum);
            }
        }

        return scoreById.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(Math.max(topN, 0))
                // 融合分作为最终排序分写入 score；原始向量分仍保留在 metadata.distance。
                .map(e -> RagDocuments.withRankScore(byId.get(e.getKey()), e.getValue(), "rrf_score"))
                .toList();
    }

    /** 便捷重载：向量 + 关键词两路融合。 */
    static List<Document> fuse(List<Document> vectorHits, List<Document> keywordHits,
                               double vectorWeight, double keywordWeight, int k, int topN) {
        return weightedReciprocalRankFusion(
                List.of(vectorHits, keywordHits),
                List.of(vectorWeight, keywordWeight),
                k, topN);
    }
}
