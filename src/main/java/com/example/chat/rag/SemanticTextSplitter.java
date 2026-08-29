package com.example.chat.rag;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义切分器：按"相邻句子语义是否连贯"决定断点，而非靠固定长度/标点。
 *
 * <p>流程：拆句 → 批量 embedding → 算相邻句子余弦距离 → 距离超过{@code 分位阈值}处断开（话题转折）→
 * 组成块。块过长时退回 {@link RecursiveTokenTextSplitter} 做长度兜底。
 *
 * <p><b>成本提示</b>：入库时要对每个句子调一次 embedding（批量发送），比递归切分慢且更贵；
 * 适合"主题跳跃大、检索精度要求高"的文档。断点用分位数自适应，无需逐文档手调阈值。
 */
public class SemanticTextSplitter extends TextSplitter {

    // 句子边界：中文句末标点、英文 .（后随空白，避免切断小数）、换行。分隔符保留在句子末尾。
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[。！？；…])|(?<=\\.)(?=\\s)|(?<=[!?])(?=\\s)|(?<=\\n)");

    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private final EmbeddingModel embeddingModel;
    private final int maxTokens;
    private final double breakpointPercentile;
    private final int embeddingBatchSize;
    private final int embeddingMaxTokens;
    private final RecursiveTokenTextSplitter lengthFallback;

    public SemanticTextSplitter(EmbeddingModel embeddingModel, int maxTokens, int chunkOverlap,
                                double breakpointPercentile, int embeddingBatchSize, int embeddingMaxTokens) {
        Assert.notNull(embeddingModel, "embeddingModel 不能为空");
        Assert.isTrue(maxTokens > 0, "maxTokens 必须为正");
        Assert.isTrue(breakpointPercentile > 0 && breakpointPercentile < 100,
                "breakpointPercentile 必须在 (0, 100) 区间内");
        Assert.isTrue(embeddingBatchSize > 0, "embeddingBatchSize 必须为正");
        Assert.isTrue(embeddingMaxTokens > 0, "embeddingMaxTokens 必须为正");
        this.embeddingModel = embeddingModel;
        this.maxTokens = maxTokens;
        this.breakpointPercentile = breakpointPercentile;
        this.embeddingBatchSize = embeddingBatchSize;
        this.embeddingMaxTokens = embeddingMaxTokens;
        this.lengthFallback = new RecursiveTokenTextSplitter(maxTokens, chunkOverlap);
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> sentences = splitSentences(text);
        // 句子太少不值得算语义断点，直接做长度兜底。
        if (sentences.size() < 3) {
            return emitWithLengthCap(text);
        }

        List<float[]> embeddings = embedInBatches(sentences);
        double[] distances = new double[sentences.size() - 1];
        for (int i = 0; i < distances.length; i++) {
            distances[i] = cosineDistance(embeddings.get(i), embeddings.get(i + 1));
        }
        double threshold = percentile(distances, this.breakpointPercentile);

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(sentences.get(0));
        for (int i = 1; i < sentences.size(); i++) {
            if (distances[i - 1] > threshold) {
                chunks.addAll(emitWithLengthCap(current.toString()));
                current.setLength(0);
            }
            current.append(sentences.get(i));
        }
        chunks.addAll(emitWithLengthCap(current.toString()));
        return chunks;
    }

    /** 语义块若超过 token 上限，退回递归切分做长度兜底；否则原样作为一个块。 */
    private List<String> emitWithLengthCap(String group) {
        String text = group.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        if (tokenCount(text) <= this.maxTokens) {
            return List.of(text);
        }
        return this.lengthFallback.split(new Document(text)).stream()
                .map(Document::getText)
                .filter(t -> t != null && !t.isBlank())
                .toList();
    }

    /** 按 {@code embeddingBatchSize} 分批 embed，避免一次性把全部句子发出去超第三方端点的单次条数上限。 */
    private List<float[]> embedInBatches(List<String> sentences) {
        List<float[]> embeddings = new ArrayList<>(sentences.size());
        for (int start = 0; start < sentences.size(); start += this.embeddingBatchSize) {
            int end = Math.min(start + this.embeddingBatchSize, sentences.size());
            embeddings.addAll(this.embeddingModel.embed(sentences.subList(start, end)));
        }
        return embeddings;
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String s : SENTENCE_BOUNDARY.split(text)) {
            if (s.isBlank()) {
                continue;
            }
            // 单句超过 embedding 单条 token 上限时，先用长度兜底切细，避免 embed 调用直接被端点拒绝报错。
            if (tokenCount(s) <= this.embeddingMaxTokens) {
                sentences.add(s);
            }
            else {
                for (Document piece : this.lengthFallback.split(new Document(s))) {
                    if (piece.getText() != null && !piece.getText().isBlank()) {
                        sentences.add(piece.getText());
                    }
                }
            }
        }
        return sentences;
    }

    private static double cosineDistance(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 1.0;
        }
        return 1.0 - dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 取距离数组的第 p 分位值作为断点阈值。 */
    private static double percentile(double[] values, double p) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private int tokenCount(String text) {
        return text.isEmpty() ? 0 : this.encoding.countTokens(text);
    }
}
