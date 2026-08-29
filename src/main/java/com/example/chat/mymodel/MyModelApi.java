package com.example.chat.mymodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 自定义模型 HTTP 客户端层。封装 {@code /call} (同步) 与 {@code /callStream} (SSE 流式)
 * 两个端点。请求体与 OpenAI 协议无关，使用业务方私有 schema：
 * {@code query / system / history / modelName / temperature / thinking / tools / userId / jobType}。
 */
public class MyModelApi {

    private static final Logger log = LoggerFactory.getLogger(MyModelApi.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final WebClient webClient;

    public MyModelApi(String baseUrl,
                      RestClient.Builder restClientBuilder,
                      WebClient.Builder webClientBuilder) {
        this.restClient = restClientBuilder.clone().baseUrl(baseUrl).build();
        this.webClient = webClientBuilder.clone().baseUrl(baseUrl).build();
    }

    /** 调用同步端点 {@code POST /call}，返回完整结果。 */
    public CallResult call(ChatRequest request) {
        return this.restClient.post()
                .uri("/call")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(CallResult.class);
    }

    /** 调用流式端点 {@code POST /callStream}，逐帧返回 {@link Chunk}。 */
    public Flux<Chunk> callStream(ChatRequest request) {
        return this.webClient.post()
                .uri("/callStream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> line != null && !line.isBlank())
                // 兼容 SSE "data:" 前缀 和 裸 JSON
                .map(line -> line.startsWith("data:") ? line.substring(5).trim() : line.trim())
                .filter(json -> !json.isEmpty())
                .mapNotNull(json -> {
                    try {
                        return MAPPER.readValue(json, Chunk.class);
                    } catch (Exception e) {
                        log.warn("Skipping unparsable SSE line: {}", json);
                        return null;
                    }
                });
    }

    // ────────────── DTO ──────────────

    /**
     * 请求体。所有字段都可选；接口必填的实际上只有 {@code query}/{@code modelName}。
     *
     * <p>{@code assistantId} 是智能体 ID，仅用于 LLM 服务侧的调用统计（按助手维度
     * 聚合 token / 调用次数等指标），模型推理本身不会消费这个字段，业务方接收即可。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatRequest(
            @JsonProperty("query") String query,
            @JsonProperty("system") String system,
            @JsonProperty("modelName") String modelName,
            @JsonProperty("temperature") Double temperature,
            @JsonProperty("thinking") String thinking,
            @JsonProperty("userId") Long userId,
            @JsonProperty("assistantId") Long assistantId,
            @JsonProperty("jobType") Integer jobType,
            @JsonProperty("history") List<HistoryMessage> history,
            @JsonProperty("tools") List<Map<String, Object>> tools
    ) {}

    /**
     * history 中的一条消息。
     * <ul>
     *   <li>{@code role=1} user：{@code content} 为用户文本</li>
     *   <li>{@code role=2} assistant：{@code content} 为助手文本；如有工具调用，
     *       将 OpenAI 兼容的 tool_calls 数组序列化为 JSON 字符串放入 {@code toolCall}</li>
     *   <li>{@code role=3} tool：{@code content} 为工具返回结果，并填 {@code toolCallId}/{@code name}</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryMessage(
            @JsonProperty("role") int role,
            @JsonProperty("content") String content,
            @JsonProperty("toolCall") String toolCall,
            @JsonProperty("toolCallId") String toolCallId,
            @JsonProperty("name") String name
    ) {
        public static HistoryMessage user(String content) {
            return new HistoryMessage(1, content, null, null, null);
        }
        public static HistoryMessage assistant(String content, String toolCallJson) {
            return new HistoryMessage(2, content == null ? "" : content, toolCallJson, null, null);
        }
        public static HistoryMessage tool(String content, String toolCallId, String name) {
            return new HistoryMessage(3, content == null ? "" : content, null, toolCallId, name);
        }
    }

    /** 同步端点 {@code /call} 的返回体。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallResult(
            @JsonProperty("code") Integer code,
            @JsonProperty("message") String message,
            @JsonProperty("result") String result
    ) {}

    /**
     * 流式端点单帧。
     * <ul>
     *   <li>{@code data} 普通文本增量</li>
     *   <li>{@code reasoningContent} 思考内容（非最终回答）</li>
     *   <li>{@code toolCalls} 模型发起的工具调用，JSON 字符串，OpenAI 兼容格式</li>
     *   <li>{@code previousResponseId} 流末尾的元信息帧，忽略</li>
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chunk(
            @JsonProperty("data") String data,
            @JsonProperty("reasoningContent") String reasoningContent,
            @JsonProperty("toolCalls") String toolCalls,
            @JsonProperty("previousResponseId") String previousResponseId
    ) {}

    /**
     * 解析 {@code Chunk.toolCalls} JSON 字符串为结构化对象。
     * 格式样例：
     * <pre>[{"function":{"arguments":"{...}","name":"get_weather"},
     *       "id":"...","index":0,"type":"function"}]</pre>
     */
    public static List<ToolCallChunk> parseToolCalls(String toolCallsJson) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(
                    toolCallsJson,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, ToolCallChunk.class));
        } catch (Exception e) {
            log.warn("Failed to parse toolCalls payload: {}", toolCallsJson, e);
            return List.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallChunk(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("index") Integer index,
            @JsonProperty("function") FunctionCall function
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCall(
            @JsonProperty("name") String name,
            @JsonProperty("arguments") String arguments
    ) {}
}
