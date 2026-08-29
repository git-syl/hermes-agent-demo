package com.example.chat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE;

/**
 * 知识库助手接口：向量语义检索 + 检索增强问答。检索与问答都支持 per-request 动态参数
 * （相似度阈值、topK、查询改写、空上下文策略），并按 {@code tenantId} 做租户隔离。
 *
 * <pre>
 * # 语义搜索（可选 metadata 过滤）
 * curl 'http://localhost:8080/ai-health-assistant/search?query=一颗苹果树的故事&tenantId=acme&topK=5'
 * curl 'http://localhost:8080/ai-health-assistant/search?query=...&tenantId=acme&metadata=%7B%22category%22%3A%22novel%22%7D'
 * # 问答（SSE 流式）
 * curl -N 'http://localhost:8080/ai-health-assistant/chat?userMessageContent=讲了什么&tenantId=acme&chatId=11&topK=3&expanderNumberOfQueries=2'
 * </pre>
 */
@RestController
@RequestMapping("/ai-health-assistant")
public class RagAssistantController {

    private final RagService ragService;
    private final ObjectMapper objectMapper;

    public RagAssistantController(RagService ragService, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.objectMapper = objectMapper;
    }

    /**
     * 向量语义搜索：返回命中的文档块文本、元数据与相似度分数，限定在指定租户内。
     *
     * @param tenantId            租户标识，必传；只在该租户的文档里检索
     * @param metadata            附加元数据过滤的 JSON 对象（如 {@code {"category":"novel"}}），与 tenantId 做 AND；可不传
     * @param similarityThreshold 相似度阈值（0~1），越高越严
     * @param topK                返回条数
     */
    @GetMapping("/search")
    public List<SearchHit> search(
            @RequestParam String query,
            @RequestParam String tenantId,
            @RequestParam(name = "metadata", required = false) String metadata,
            @RequestParam(defaultValue = "0.5") Double similarityThreshold,
            @RequestParam(defaultValue = "4") Integer topK) {
        Map<String, Object> filters = RagParams.parseMetadata(objectMapper, metadata);
        return ragService.search(query, tenantId, filters, similarityThreshold, topK).stream()
                .map(d -> new SearchHit(d.getId(), d.getText(), d.getMetadata(), d.getScore(), originScore(d)))
                .toList();
    }

    /**
     * 向量原始相似度（{@code 1 - distance}）。开了 rerank/混合检索后 {@code score} 是最终排序分，
     * 这里另给一份"未重排前的向量分"便于对比；纯关键词命中的块没有向量分，返回 {@code null}。
     */
    private static Double originScore(Document d) {
        Object distance = d.getMetadata().get(DocumentMetadata.DISTANCE.value());
        return distance instanceof Number n ? 1.0 - n.doubleValue() : null;
    }

    /**
     * RAG 问答（SSE 流式）：基于知识库检索结果作答，按 chatId 维持多轮上下文，检索限定在指定租户内。
     *
     * @param tenantId                租户标识，必传；只在该租户的文档里检索
     * @param metadata                附加元数据过滤的 JSON 对象（如 {@code {"category":"novel"}}），与 tenantId 做 AND；可不传
     * @param chatId                  会话 ID，省略则用 {@code default}
     * @param similarityThreshold     相似度阈值（0~1）
     * @param topK                    召回条数
     * @param expanderNumberOfQueries 查询改写条数，&gt;0 启用多查询扩展改善召回；0 关闭
     * @param allowEmptyContext       命中为空时是否仍让模型作答
     */
    @GetMapping(value = "/chat", produces = TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatResponse> chat(
            @RequestParam String userMessageContent,
            @RequestParam String tenantId,
            @RequestParam(name = "metadata", required = false) String metadata,
            @RequestParam(name = "chatId", required = false) String chatId,
            @RequestParam(defaultValue = "0.5") Double similarityThreshold,
            @RequestParam(defaultValue = "3") Integer topK,
            @RequestParam(defaultValue = "0") Integer expanderNumberOfQueries,
            @RequestParam(defaultValue = "true") Boolean allowEmptyContext) {
        Map<String, Object> filters = RagParams.parseMetadata(objectMapper, metadata);
        return ragService.chat(chatId, userMessageContent, tenantId, filters,
                similarityThreshold, topK, expanderNumberOfQueries, allowEmptyContext);
    }

    /**
     * 检索命中项的对外视图，避免直接暴露 {@link Document} 内部结构。
     *
     * @param score       最终排序分：纯向量=向量相似度，混合=RRF 分，rerank=rerank 分（结果即按它降序）
     * @param originScore 向量原始相似度（{@code 1 - distance}）；纯关键词命中为 {@code null}。便于对比重排前后
     */
    public record SearchHit(String id, String text, Map<String, Object> metadata, Double score, Double originScore) {
    }
}
