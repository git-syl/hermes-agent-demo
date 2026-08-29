package com.example.chat.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

/**
 * 按第三方 embedding 端点的真实限额给入库文档分批的 {@link BatchingStrategy}：
 * <b>每批最多 {@code maxBatchSize} 条，且每条文本 token 数不超过 {@code maxInputTokenCount}</b>
 * （如 DashScope text-embedding-v4：单次最多 10 条、每条 ≤ 8192 token）。
 *
 * <p>Spring AI 默认的 {@code TokenCountBatchingStrategy} 只按"一批 token 总和"切分、不限条数，
 * 面对"单次最多 N 条"的限额会超限报错；这里以条数为主、并对单条 token 做 fail-fast 校验。
 * 单条文本超过 token 上限多半是切分配置不当，直接抛错而非静默截断。
 */
public class BoundedBatchingStrategy implements BatchingStrategy {

    private final TokenCountEstimator tokenCountEstimator = new JTokkitTokenCountEstimator();
    private final int maxBatchSize;
    private final int maxInputTokenCount;

    public BoundedBatchingStrategy(int maxBatchSize, int maxInputTokenCount) {
        Assert.isTrue(maxBatchSize > 0, "maxBatchSize 必须为正");
        Assert.isTrue(maxInputTokenCount > 0, "maxInputTokenCount 必须为正");
        this.maxBatchSize = maxBatchSize;
        this.maxInputTokenCount = maxInputTokenCount;
    }

    @Override
    public List<List<Document>> batch(List<Document> documents) {
        List<List<Document>> batches = new ArrayList<>();
        List<Document> current = new ArrayList<>();

        for (Document document : documents) {
            int tokens = tokenCountEstimator.estimate(document.getText());
            Assert.isTrue(tokens <= this.maxInputTokenCount,
                    "单条文本 token 数(" + tokens + ")超过 embedding 单条上限(" + this.maxInputTokenCount
                            + ")，请调小 chat.rag.chunk-tokens 或检查切分配置");

            if (current.size() >= this.maxBatchSize) {
                batches.add(current);
                current = new ArrayList<>();
            }
            current.add(document);
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }
}
