package com.example.chat.mymodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring AI 适配器，将私有 HTTP 接口接入 Spring AI {@link ChatModel} 抽象。
 *
 * <h3>消息映射</h3>
 * <ul>
 *   <li>{@link MessageType#SYSTEM SYSTEM} 消息合并为 {@code system}</li>
 *   <li>除 SYSTEM/最后一条以外的消息按顺序写入 {@code history}
 *       (USER→role=1，ASSISTANT/TOOL→role=2/1)</li>
 *   <li>最后一条消息文本写入 {@code query}</li>
 * </ul>
 *
 * <h3>工具调用</h3>
 * 该模型协议使用 OpenAI 兼容的 {@code tools} 字段。本类把 Spring AI 的
 * {@link ToolCallback} 转为 {@code [{type:"function", function:{name, description, parameters}}]}
 * 后随请求发送；响应中的 {@code toolCalls} 解析回 {@link AssistantMessage.ToolCall}，由 Spring AI
 * 的 {@code ToolCallingAdvisor / ToolCallingManager} 完成工具执行循环。
 */
public class MyModelChatModel implements ChatModel, StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(MyModelChatModel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MyModelApi api;
    private final MyModelProperties properties;

    public MyModelChatModel(MyModelApi api, MyModelProperties properties) {
        this.api = api;
        this.properties = properties;
    }

    // ────────────── ChatModel ──────────────

    @Override
    public ChatResponse call(Prompt prompt) {
        MyModelApi.ChatRequest request = buildRequest(prompt);
        MyModelApi.CallResult result = api.call(request);

        String text = (result != null && result.result() != null) ? result.result() : "";
        AssistantMessage msg = new AssistantMessage(text);
        ChatGenerationMetadata meta = ChatGenerationMetadata.builder().finishReason("STOP").build();
        // TODO(reasoning): MyModel 同步 /call 端点目前只返回最终文本，没有 reasoning 字段。
        //   服务端补上后，在 CallResult 里加 reasoningContent 字段，并在这里多发一个带
        //   metadata.thinking=TRUE 的 Generation（顺序：thinking → 正文），上游
        //   ChatService.extractReasoningAndText 即可正确拆分到 ChatResponse.reasoning。
        return new ChatResponse(List.of(new Generation(msg, meta)));
    }

    // ────────────── StreamingChatModel ──────────────

    /**
     * 流式调用映射规则：
     * <ul>
     *   <li>{@code toolCalls} → 聚合为一条带 {@code finishReason=TOOL_CALLS} 的 ChatResponse，
     *       让上游 ToolCallingAdvisor 继续执行工具。</li>
     *   <li>{@code reasoningContent} → 深度思考增量。包成一条 {@link AssistantMessage}，
     *       text 为思考片段，metadata 带 {@code thinking=TRUE} 标记。这样上层
     *       {@code ChatService.toEvents()} 会按 reasoning 事件类型派发（沿用 Anthropic
     *       流式 thinking 块的同款约定），不会和正文 token 混在一起。</li>
     *   <li>{@code data} → 普通文本 token。</li>
     * </ul>
     * 三者在协议层通常互斥（同一帧只有一个非空字段）；这里按 toolCalls > reasoning > data
     * 的顺序判定，避免空字符串误判。
     *
     * <p>流末尾会追加一条 {@code finishReason="STOP"} 的空帧，
     * 上游 {@code ChatService.toEvents()} 据此发出 SSE {@code finish} 事件，
     * 对齐 DeepSeek/OpenAI 等模型的流终止语义。
     * 若本轮已经发过 {@code TOOL_CALLS} 帧，则跳过 STOP——
     * 工具调用本身就是这一轮 stream 的终止标记，再补会被 ToolCallingAdvisor 误判为对话结束。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 整段用 Flux.defer 包裹：每次订阅独立持有 finishEmitted/请求对象，避免
        // 上游 retry/replay 等场景下 mutable 闭包状态被跨订阅复用，导致重复 stream
        // 时漏发 STOP 终止帧。
        return Flux.defer(() -> {
            MyModelApi.ChatRequest request = buildRequest(prompt);
            AtomicBoolean finishEmitted = new AtomicBoolean(false);

            return api.callStream(request)
                    .concatMap(chunk -> {
                        if (chunk.toolCalls() != null && !chunk.toolCalls().isBlank()) {
                            List<MyModelApi.ToolCallChunk> calls = MyModelApi.parseToolCalls(chunk.toolCalls());
                            if (calls.isEmpty()) {
                                return Flux.empty();
                            }
                            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>(calls.size());
                            for (MyModelApi.ToolCallChunk c : calls) {
                                if (c.function() == null) continue;
                                toolCalls.add(new AssistantMessage.ToolCall(
                                        c.id() != null ? c.id() : "",
                                        c.type() != null ? c.type() : "function",
                                        c.function().name(),
                                        c.function().arguments() != null ? c.function().arguments() : "{}"));
                            }
                            AssistantMessage msg = AssistantMessage.builder()
                                    .content("")
                                    .toolCalls(toolCalls)
                                    .build();
                            ChatGenerationMetadata meta = ChatGenerationMetadata.builder()
                                    .finishReason("TOOL_CALLS")
                                    .build();
                            finishEmitted.set(true);
                            return Flux.just(new ChatResponse(List.of(new Generation(msg, meta))));
                        }
                        if (chunk.reasoningContent() != null && !chunk.reasoningContent().isEmpty()) {
                            AssistantMessage msg = AssistantMessage.builder()
                                    .content(chunk.reasoningContent())
                                    .properties(Map.of("thinking", Boolean.TRUE))
                                    .build();
                            return Flux.just(new ChatResponse(List.of(new Generation(msg))));
                        }
                        if (chunk.data() != null && !chunk.data().isEmpty()) {
                            AssistantMessage msg = new AssistantMessage(chunk.data());
                            return Flux.just(new ChatResponse(List.of(new Generation(msg))));
                        }
                        return Flux.empty();
                    })
                    .concatWith(Flux.defer(() -> {
                        if (finishEmitted.get()) {
                            return Flux.empty();
                        }
                        AssistantMessage msg = new AssistantMessage("");
                        ChatGenerationMetadata meta = ChatGenerationMetadata.builder()
                                .finishReason("STOP")
                                .build();
                        return Flux.just(new ChatResponse(List.of(new Generation(msg, meta))));
                    }));
        });
    }

    /**
     * RC1 起 Spring AI 内部统一改调 {@code getOptions()}（见 {@code DefaultChatClientUtils#toChatClientRequest}
     * 和 {@code ChatModel#buildRequestPrompt}）。返回类型协变成 {@link MyModelChatOptions}，
     * 保证下游 {@code ToolCallingAdvisor} 的 {@code instanceof ToolCallingChatOptions} 校验能过——
     * 否则 mutate→combineWith 出来的 builder 退化成普通 {@link ChatOptions.Builder}，
     * 工具调用走不通。
     *
     * <p>userId 不走配置，由 /chat/stream 请求入参透传（在 ChatService 里填入 options）。
     */
    @Override
    public MyModelChatOptions getOptions() {
        return MyModelChatOptions.builder()
                .model(properties.getModel())
                .temperature(properties.getTemperature())
                .thinking(properties.getThinking())
                .jobType(properties.getJobType())
                .build();
    }

    /** @deprecated 用 {@link #getOptions()}；保留仅为兼容仍走老接口的调用方，RC1 起 Spring AI 内部已切换。 */
    @Deprecated(forRemoval = true)
    @Override
    public ChatOptions getDefaultOptions() {
        return getOptions();
    }

    // ────────────── Prompt → ChatRequest ──────────────

    private MyModelApi.ChatRequest buildRequest(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();

        // 1) system 合并
        StringBuilder system = new StringBuilder();
        List<Message> conversation = new ArrayList<>();
        for (Message m : messages) {
            if (m.getMessageType() == MessageType.SYSTEM) {
                if (system.length() > 0) system.append('\n');
                system.append(m.getText() == null ? "" : m.getText());
            } else {
                conversation.add(m);
            }
        }

        // 2) 拆分 query + history。
        //    - 最后一条是 USER  → 作为 query，其余进 history
        //    - 最后一条是 TOOL  → query="", 全部进 history（工具循环回传场景）
        //    - 最后一条是其它   → 同样全部进 history，query=""
        String query = "";
        List<MyModelApi.HistoryMessage> history = new ArrayList<>();
        if (!conversation.isEmpty()) {
            Message last = conversation.get(conversation.size() - 1);
            int historyEnd = conversation.size();
            if (last.getMessageType() == MessageType.USER) {
                query = textOf(last);
                historyEnd = conversation.size() - 1;
            }
            for (int i = 0; i < historyEnd; i++) {
                appendHistory(history, conversation.get(i));
            }
        }

        // 3) 合并 options 与默认值
        MyModelChatOptions opts = optionsOf(prompt);

        String finalModel       = firstNonBlank(opts.getModel(),       properties.getModel());
        Double finalTemperature = opts.getTemperature() != null ? opts.getTemperature() : properties.getTemperature();
        String finalThinking    = firstNonBlank(opts.getThinking(),    properties.getThinking());
        // userId / assistantId 都只由请求透传，不从配置读取。
        Long finalUserId        = opts.getUserId();
        Long finalAssistantId   = opts.getAssistantId();
        Integer finalJobType    = opts.getJobType() != null ? opts.getJobType() : (Integer) properties.getJobType();

        // 4) 工具列表：把 Spring AI 注入的 ToolCallback 转为 OpenAI 兼容的 tools schema
        List<Map<String, Object>> tools = toolsOf(opts);

        if (log.isDebugEnabled()) {
            log.debug("MyModel request: model={}, tools={}, historySize={}, queryLen={}",
                    finalModel,
                    tools.isEmpty() ? "[]" : tools.stream().map(t -> {
                        Object f = t.get("function");
                        return f instanceof Map<?, ?> mf ? String.valueOf(mf.get("name")) : "?";
                    }).toList(),
                    history.size(),
                    query.length());
        }

        return new MyModelApi.ChatRequest(
                query,
                system.length() == 0 ? null : system.toString(),
                finalModel,
                finalTemperature,
                finalThinking,
                finalUserId,
                finalAssistantId,
                finalJobType,
                history.isEmpty() ? null : history,
                tools.isEmpty() ? null : tools
        );
    }

    /** 把 Spring AI 的 Message 按当前接口的 role 协议追加到 history 中。 */
    private static void appendHistory(List<MyModelApi.HistoryMessage> history, Message m) {
        switch (m.getMessageType()) {
            case USER -> history.add(MyModelApi.HistoryMessage.user(textOf(m)));
            case ASSISTANT -> {
                String toolCallJson = null;
                if (m instanceof AssistantMessage am
                        && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                    toolCallJson = serializeToolCalls(am.getToolCalls());
                }
                history.add(MyModelApi.HistoryMessage.assistant(textOf(m), toolCallJson));
            }
            case TOOL -> {
                if (m instanceof ToolResponseMessage trm) {
                    for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                        history.add(MyModelApi.HistoryMessage.tool(
                                r.responseData(), r.id(), r.name()));
                    }
                } else {
                    history.add(MyModelApi.HistoryMessage.tool(textOf(m), null, null));
                }
            }
            default -> history.add(MyModelApi.HistoryMessage.user(textOf(m)));
        }
    }

    /**
     * 把 Spring AI 的 ToolCall 列表序列化为接口约定的 OpenAI 兼容 JSON 字符串。
     * 形如 {@code [{"function":{"arguments":"...","name":"..."},"id":"...","index":0,"type":"function"}]}.
     */
    private static String serializeToolCalls(List<AssistantMessage.ToolCall> calls) {
        List<Map<String, Object>> arr = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            AssistantMessage.ToolCall c = calls.get(i);
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("arguments", c.arguments() != null ? c.arguments() : "{}");
            fn.put("name", c.name());
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("function", fn);
            tc.put("id", c.id() != null ? c.id() : "");
            tc.put("index", i);
            tc.put("type", c.type() != null ? c.type() : "function");
            arr.add(tc);
        }
        try {
            return MAPPER.writeValueAsString(arr);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize tool calls: {}", e.getMessage());
            return "[]";
        }
    }

    private MyModelChatOptions optionsOf(Prompt prompt) {
        ChatOptions po = prompt.getOptions();
        if (po instanceof MyModelChatOptions mo) {
            return mo;
        }
        // Fallback：若 Spring AI 把我们的 options 降级成 DefaultToolCallingChatOptions
        // 或其他 ToolCallingChatOptions，也要把 toolCallbacks/temperature 等关键字段抢救出来。
        // RC1 起 options 强制不可变，只能走 builder 重建。
        MyModelChatOptions.Builder b = MyModelChatOptions.builder();
        if (po instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tco) {
            b.model(tco.getModel());
            b.temperature(tco.getTemperature());
            if (tco.getToolCallbacks() != null) b.toolCallbacks(tco.getToolCallbacks());
            if (tco.getToolContext() != null)   b.toolContext(tco.getToolContext());
        } else if (po != null) {
            b.model(po.getModel());
            b.temperature(po.getTemperature());
        }
        return b.build();
    }

    private static String textOf(Message m) {
        String t = m.getText();
        return t == null ? "" : t;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    /** 将 ToolCallback 列表转为 OpenAI 兼容的 tools 描述。 */
    private static List<Map<String, Object>> toolsOf(MyModelChatOptions opts) {
        List<ToolCallback> callbacks = opts.getToolCallbacks();
        if (callbacks == null || callbacks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(callbacks.size());
        for (ToolCallback cb : callbacks) {
            var def = cb.getToolDefinition();
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", def.name());
            if (def.description() != null) {
                function.put("description", def.description());
            }
            function.put("parameters", parseSchema(def.inputSchema()));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            result.add(tool);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return MAPPER.readValue(schema, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Invalid JSON schema for tool, falling back to empty object: {}", schema);
            return Map.of("type", "object", "properties", Map.of());
        }
    }
}
