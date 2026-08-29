package com.example.chat.rag;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.util.Assert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 递归字符切分器（token 计长 + 重叠），面向高质量 RAG 召回。
 *
 * <p>对标 LangChain 的 {@code RecursiveCharacterTextSplitter}，并做了两点增强：
 * <ol>
 *   <li><b>token 计长</b>：用 jtokkit（cl100k）按 token 而非字符数衡量块大小，贴合 embedding 模型的上下文上限；</li>
 *   <li><b>中文友好的分隔符层级</b>：段落 → 行 → 中英文句末标点 → 逗号 → 空格 → 字符，
 *       优先在语义边界断开，只有当一段仍超长才降级到更细的分隔符递归。</li>
 * </ol>
 *
 * <p>相比 Spring AI 自带的 {@code TokenTextSplitter}，本切分器：
 * <ul>
 *   <li>支持<b>块间重叠</b>（{@code chunkOverlap}）——跨块保留上下文，显著改善边界处的检索召回；</li>
 *   <li>用<b>递归分隔符</b>而非单一标点扫描，能更稳地保住"段落/句子"这类语义单元。</li>
 * </ul>
 *
 * <p>分隔符在切分时<b>保留在前一段末尾</b>（如句号跟随它结束的那句话），因此合并时直接拼接、不重复插分隔符。
 */
public class RecursiveTokenTextSplitter extends TextSplitter {

    /**
     * 默认分隔符层级（从粗到细）。{@code ""} 为兜底字符级切分，保证再长也能切下去。
     * 英文句末用 {@code ". "} 带空格，避免把 {@code 3.14} 这类小数从中间切断；中文句末直接用全角标点。
     */
    private static final List<String> DEFAULT_SEPARATORS = List.of(
            "\n\n", "\n", "。", "！", "？", "；", "…", "．", ". ", "! ", "? ", "; ", "，", ", ", " ", "");

    private final Encoding encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    /** 目标块大小（token 数）。 */
    private final int chunkSize;

    /** 相邻块的重叠 token 数，必须小于 {@link #chunkSize}。 */
    private final int chunkOverlap;

    private final List<String> separators;

    public RecursiveTokenTextSplitter(int chunkSize, int chunkOverlap) {
        this(chunkSize, chunkOverlap, DEFAULT_SEPARATORS);
    }

    public RecursiveTokenTextSplitter(int chunkSize, int chunkOverlap, List<String> separators) {
        Assert.isTrue(chunkSize > 0, "chunkSize 必须为正");
        Assert.isTrue(chunkOverlap >= 0 && chunkOverlap < chunkSize, "chunkOverlap 必须在 [0, chunkSize) 区间内");
        Assert.notEmpty(separators, "separators 不能为空");
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.separators = separators;
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return splitRecursive(text, this.separators);
    }

    /**
     * 选当前文本里命中的最粗分隔符切分；对仍超长的片段，用更细一级分隔符递归，
     * 其余正常片段交给 {@link #mergeWithOverlap} 合并成带重叠的块。
     */
    private List<String> splitRecursive(String text, List<String> separators) {
        // 选定本层分隔符，并算出"更细一级"的分隔符集合用于递归。
        String separator = separators.get(separators.size() - 1);
        List<String> finerSeparators = List.of();
        for (int i = 0; i < separators.size(); i++) {
            String s = separators.get(i);
            if (s.isEmpty() || text.contains(s)) {
                separator = s;
                finerSeparators = separators.subList(i + 1, separators.size());
                break;
            }
        }

        List<String> result = new ArrayList<>();
        List<String> mergeable = new ArrayList<>();
        for (String piece : splitBySeparator(text, separator)) {
            if (piece.isEmpty()) {
                continue;
            }
            if (tokenCount(piece) < this.chunkSize) {
                mergeable.add(piece);
                continue;
            }
            // 片段仍超长：先把已攒的正常片段合并落袋，再对超长片段降级递归。
            if (!mergeable.isEmpty()) {
                result.addAll(mergeWithOverlap(mergeable));
                mergeable.clear();
            }
            if (finerSeparators.isEmpty()) {
                result.add(piece); // 已无更细分隔符（字符级也超长，极端情况），原样保留
            }
            else {
                result.addAll(splitRecursive(piece, finerSeparators));
            }
        }
        if (!mergeable.isEmpty()) {
            result.addAll(mergeWithOverlap(mergeable));
        }
        return result;
    }

    /**
     * 把若干小片段贪心合并成 ≤ {@link #chunkSize} token 的块；每次落袋后，从块头部回退到约
     * {@link #chunkOverlap} token，作为与下一块的重叠。分隔符已在片段末尾，故直接拼接。
     */
    private List<String> mergeWithOverlap(List<String> pieces) {
        List<String> chunks = new ArrayList<>();
        Deque<String> window = new ArrayDeque<>();
        int total = 0;
        for (String piece : pieces) {
            int len = tokenCount(piece);
            if (total + len > this.chunkSize && !window.isEmpty()) {
                chunks.add(join(window));
                while (total > this.chunkOverlap && !window.isEmpty()) {
                    total -= tokenCount(window.peekFirst());
                    window.pollFirst();
                }
            }
            window.addLast(piece);
            total += len;
        }
        if (!window.isEmpty()) {
            chunks.add(join(window));
        }
        return chunks;
    }

    /** 按分隔符切分，并把分隔符保留在前一段末尾；空分隔符表示按字符（码点）切。 */
    private static List<String> splitBySeparator(String text, String separator) {
        if (separator.isEmpty()) {
            List<String> chars = new ArrayList<>();
            text.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
            return chars;
        }
        List<String> result = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = text.indexOf(separator, start)) >= 0) {
            int end = idx + separator.length();
            result.add(text.substring(start, end));
            start = end;
        }
        if (start < text.length()) {
            result.add(text.substring(start));
        }
        return result;
    }

    private static String join(Deque<String> window) {
        return String.join("", window).strip();
    }

    private int tokenCount(String text) {
        return text.isEmpty() ? 0 : this.encoding.countTokens(text);
    }
}
