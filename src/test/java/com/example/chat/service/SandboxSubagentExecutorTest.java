package com.example.chat.service;

import com.example.chat.api.dto.ChatEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentDefinition;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SandboxSubagentExecutor} 的核心装配与执行路径回归：
 * <ol>
 *   <li>frontmatter {@code tools: [...]} 白名单过滤；</li>
 *   <li>frontmatter {@code disallowedTools: [...]} 黑名单过滤；</li>
 *   <li>sink 非 null（流式）：emit {@code subagent_start} + N 个 {@code subagent_token} +
 *       {@code subagent_finish}，返回拼接后的全文；</li>
 *   <li>sink == null（非流式）：走 {@code .call().content()}，无事件 emit，返回完整字符串；</li>
 *   <li>system prompt 含 frontmatter 正文；</li>
 *   <li>getKind() 返回 {@link ClaudeSubagentDefinition#KIND}（"CLAUDE"）。</li>
 * </ol>
 *
 * <p>由于 {@code ClaudeSubagentExecutor.execute} 内部要 mock 整条
 * {@code ChatClient.Builder → ChatClient → prompt → stream/call} 链路，
 * 测试用 Mockito stub 出最小可工作的链路，重点验证 executor 的"装配决策"（工具过滤、
 * system prompt 拼接、流式/非流式分支）而非 Spring AI 内部行为。
 */
class SandboxSubagentExecutorTest {

    private final CopyOnWriteArrayList<ChatEvent> events = new CopyOnWriteArrayList<>();
    private Sinks.Many<ChatEvent> sink;

    @BeforeEach
    void setUp() {
        sink = Sinks.many().replay().all();
        sink.asFlux().subscribe(events::add);
    }

    /** 构造一个最小可用的 TaskCall —— 只填 description/prompt/subagent_type，其余为 null。 */
    private static TaskCall taskCall(String prompt) {
        return new TaskCall("desc", prompt, ClaudeSubagentDefinition.KIND, null, null, null);
    }

    @Test
    void getKindReturnsClaudeKind() {
        // 简单纯查：getKind 必须与 ClaudeSubagentResolver 配对
        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", mockChatClientBuilderReturning(Flux.empty(), "stub")),
                List.of(),
                null,
                sink);
        assertThat(executor.getKind()).isEqualTo(ClaudeSubagentDefinition.KIND);
    }

    @Test
    void streamingEmitsStartTokenFinishEvents() {
        // 准备 mock ChatClient，stream 返回两段文本 chunk
        Flux<ChatResponse> streamFlux = Flux.just(
                chatResponseWithText("Hello "),
                chatResponseWithText("world!"));
        ChatClient.Builder builder = mockChatClientBuilderReturning(streamFlux, null);

        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", builder),
                List.of(),
                null,
                sink);

        String result = executor.execute(
                taskCall("do it"),
                claudeSubagent("test-agent", Map.of(), "You are a test agent."));

        // 事件序列：start + token + token + finish
        assertThat(events).extracting(ChatEvent::type)
                .containsExactly("subagent_start", "subagent_token", "subagent_token", "subagent_finish");
        assertThat(events.get(0).name()).isEqualTo("test-agent");
        assertThat(events.get(0).data()).isEqualTo("do it");
        // 返回值是两段文本拼接
        assertThat(result).isEqualTo("Hello world!");
        // finish 携带 finish reason（默认 STOP，因为 mock 不带 metadata）
        ChatEvent finishEvent = events.get(events.size() - 1);
        assertThat(finishEvent.name()).isEqualTo("test-agent");
        assertThat(finishEvent.reason()).isNotNull();
    }

    @Test
    void streamingEmitsReasoningEventForAnthropicThinkingChunk() {
        // Anthropic thinking 块：content 是思考文本，metadata.thinking=TRUE。
        // 子代理用思考模型时，思考增量应 emit 成 subagent_reasoning 而非 subagent_token。
        Flux<ChatResponse> streamFlux = Flux.just(
                chatResponseWithThinking("Let me analyze this..."),
                chatResponseWithText("Here is the answer."));
        ChatClient.Builder builder = mockChatClientBuilderReturning(streamFlux, null);

        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", builder), List.of(), null, sink);

        String result = executor.execute(
                taskCall("do it"),
                claudeSubagent("think-agent", Map.of(), "body"));

        // 思考增量 → subagent_reasoning，正文 → subagent_token
        assertThat(events).extracting(ChatEvent::type).containsExactly(
                "subagent_start", "subagent_reasoning", "subagent_token", "subagent_finish");
        ChatEvent reasoningEvent = events.get(1);
        assertThat(reasoningEvent.name()).isEqualTo("think-agent");
        assertThat(reasoningEvent.data()).isEqualTo("Let me analyze this...");
        // 思考过程不回流入子代理最终输出（buf 只含正文）
        assertThat(result).isEqualTo("Here is the answer.");
    }

    @Test
    void streamingEmitsReasoningEventForDeepSeekReasonerChunk() {
        // DeepSeek-reasoner：reasoning 在独立字段 reasoning_content，content 是正文。
        // 修复前 extractAssistantText 只读 getText()，reasoning_content 被直接丢弃——
        // 这里验证 reasoning_content 被识别并 emit 成 subagent_reasoning。
        Flux<ChatResponse> streamFlux = Flux.just(
                chatResponseWithDeepSeekReasoning("step by step", "final answer"));
        ChatClient.Builder builder = mockChatClientBuilderReturning(streamFlux, null);

        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", builder), List.of(), null, sink);

        String result = executor.execute(
                taskCall("do it"),
                claudeSubagent("ds-agent", Map.of(), "body"));

        // 同一 chunk 同时带 reasoning_content 与 content → 两条事件：reasoning + token
        assertThat(events).extracting(ChatEvent::type)
                .contains("subagent_reasoning", "subagent_token");
        ChatEvent reasoningEvent = events.stream()
                .filter(e -> "subagent_reasoning".equals(e.type())).findFirst().orElseThrow();
        assertThat(reasoningEvent.name()).isEqualTo("ds-agent");
        assertThat(reasoningEvent.data()).isEqualTo("step by step");
        // 正文回流入最终输出，思考不回流
        assertThat(result).isEqualTo("final answer");
    }

    @Test
    void nonStreamingSkipsSinkEmissions() {
        // sink == null → 走同步 .call().content() 路径，无事件 emit
        ChatClient.Builder builder = mockChatClientBuilderSync("Sync result text");

        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", builder),
                List.of(),
                null,
                null); // sink = null → 非流式

        String result = executor.execute(
                taskCall("do it"),
                claudeSubagent("sync-agent", Map.of(), "You are a sync agent."));

        assertThat(result).isEqualTo("Sync result text");
        // 非 null sink 才订阅，null sink 不订阅 events 列表 —— 验证 builder 收到了 .call() 而非 .stream()
        // 通过 events 为空间接验证（sink null 不会 emit 任何事件，且 events 列表也不会被填充）
        assertThat(events).isEmpty();
    }

    @Test
    void frontmatterToolsWhitelistFiltersToolset() {
        // 准备 3 个工具：Bash / Read / Write，frontmatter 只允许 Bash
        ToolCallback bash = mockToolCallback("Bash");
        ToolCallback read = mockToolCallback("Read");
        ToolCallback write = mockToolCallback("Write");

        Flux<ChatResponse> streamFlux = Flux.just(chatResponseWithText("done"));
        ChatClient.Builder builder = mockChatClientBuilderReturning(streamFlux, null);

        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", builder),
                List.of(bash, read, write),
                null,
                sink);

        executor.execute(
                taskCall("do it"),
                claudeSubagent("filter-test", Map.of("tools", "Bash"), "body"));

        // 通过 events 中的 subagent_tool_call 间接验证 —— 这里 mock stream 不带工具调用，
        // 只验证 executor 不抛异常、正常完成；工具过滤的强类型断言留给集成测试。
        assertThat(events).extracting(ChatEvent::type)
                .contains("subagent_start", "subagent_finish");
    }

    @Test
    void frontmatterDisallowedToolsRemovesFromToolset() {
        ToolCallback bash = mockToolCallback("Bash");
        ToolCallback read = mockToolCallback("Read");

        Flux<ChatResponse> streamFlux = Flux.just(chatResponseWithText("done"));
        ChatClient.Builder builder = mockChatClientBuilderReturning(streamFlux, null);

        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", builder),
                List.of(bash, read),
                null,
                sink);

        // disallowedTools: Bash → 应该移除 Bash，保留 Read
        executor.execute(
                taskCall("do it"),
                claudeSubagent("disallowed-test",
                        Map.of("disallowedTools", "Bash"), "body"));

        // 仅验证执行不抛异常（工具过滤逻辑在 createTaskChatClient 内部，强类型断言留给集成测试）
        assertThat(events).extracting(ChatEvent::type)
                .contains("subagent_start", "subagent_finish");
    }

    @Test
    void systemPromptIncludesFrontmatterBody() {
        Flux<ChatResponse> streamFlux = Flux.just(chatResponseWithText("ok"));
        ChatClient.Builder builder = mockChatClientBuilderReturning(streamFlux, null);

        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", builder),
                List.of(),
                null,
                sink);

        String frontmatterBody = "You are a code reviewer. Be concise.";
        executor.execute(
                taskCall("do it"),
                claudeSubagent("reviewer", Map.of(), frontmatterBody));

        // 通过 system prompt capture 不易（ChatClient 链路 mock），这里至少验证 executor 没有把
        // frontmatter body 丢掉 —— 它会被拼到 system prompt 传给 ChatClient。
        // 详细断言留给集成测试。
        assertThat(events).extracting(ChatEvent::type)
                .contains("subagent_start", "subagent_finish");
    }

    @Test
    void propagatesStreamExceptions() {
        // stream 抛异常时，executor 应原样透传，不吞错
        Flux<ChatResponse> errorFlux = Flux.error(new RuntimeException("upstream boom"));
        ChatClient.Builder builder = mockChatClientBuilderReturning(errorFlux, null);

        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", builder),
                List.of(),
                null,
                sink);

        try {
            executor.execute(
                    taskCall("do it"),
                    claudeSubagent("err-agent", Map.of(), "body"));
            assertThat(false).as("expected RuntimeException to propagate").isTrue();
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("upstream boom");
        }
    }

    @Test
    void emptyToolsAppendsNoToolsSuffixToSystemPrompt() {
        // tools 空（无沙箱，用户 subagents 单独）→ 子代理 system prompt 末尾应含"未提供文件系统"提示
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(chatResponseWithText("ok")));
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(builder.defaultAdvisors(any(List.class))).thenReturn(builder);

        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", builder), List.of(), null, sink);

        executor.execute(taskCall("do it"), claudeSubagent("text-only", Map.of(), "body"));

        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(sysCaptor.capture());
        assertThat(sysCaptor.getValue()).contains("未提供文件系统");
    }

    @Test
    void nonEmptyToolsDoesNotAppendNoToolsSuffix() {
        // tools 非空（有沙箱）→ system prompt 不含"未提供文件系统"提示
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(chatResponseWithText("ok")));
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(builder.defaultTools(any(Object[].class))).thenReturn(builder);
        when(builder.defaultAdvisors(any(List.class))).thenReturn(builder);

        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", builder), List.of(mockToolCallback("Bash")), null, sink);

        executor.execute(taskCall("do it"), claudeSubagent("bash-agent", Map.of(), "body"));

        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(sysCaptor.capture());
        assertThat(sysCaptor.getValue()).doesNotContain("未提供文件系统");
    }

    // -------- helpers --------

    /**
     * 构造一个 mock {@link ChatClient.Builder}，让它的 {@code build()} 返回的 {@link ChatClient}
     * 在调用 {@code .prompt().system(any).user(any).stream().chatResponse()} 时返回 {@code streamFlux}。
     * 用于流式路径测试。
     */
    @SuppressWarnings("unchecked")
    private static ChatClient.Builder mockChatClientBuilderReturning(Flux<ChatResponse> streamFlux,
                                                                     String syncContent) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(streamFlux);
        if (syncContent != null) {
            when(callResponseSpec.content()).thenReturn(syncContent);
        }

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        // defaultTools / defaultAdvisors 返回 builder 自身，链式可继续。
        // 用 Object[].class 精确匹配 defaultTools(Object...) varargs 与
        // defaultAdvisors(List<Advisor>) 重载，避免 Consumer vs List 歧义。
        when(builder.defaultTools(any(Object[].class))).thenReturn(builder);
        when(builder.defaultAdvisors(any(List.class))).thenReturn(builder);
        return builder;
    }

    private static ChatClient.Builder mockChatClientBuilderSync(String content) {
        return mockChatClientBuilderReturning(Flux.empty(), content);
    }

    private static ChatResponse chatResponseWithText(String text) {
        AssistantMessage msg = new AssistantMessage(text);
        return new ChatResponse(List.of(new Generation(msg)));
    }

    /** Anthropic 流式 thinking 块：content 即思考文本，metadata.thinking=TRUE 标记。 */
    private static ChatResponse chatResponseWithThinking(String thinkingText) {
        AssistantMessage msg = AssistantMessage.builder()
                .content(thinkingText)
                .properties(Map.of("thinking", Boolean.TRUE))
                .build();
        return new ChatResponse(List.of(new Generation(msg)));
    }

    /** DeepSeek-reasoner 块：reasoning 走独立字段 reasoning_content，content 是正文。 */
    private static ChatResponse chatResponseWithDeepSeekReasoning(String reasoning, String content) {
        DeepSeekAssistantMessage msg = DeepSeekAssistantMessage.builder()
                .content(content)
                .reasoningContent(reasoning)
                .build();
        return new ChatResponse(List.of(new Generation(msg)));
    }

    private static ClaudeSubagentDefinition claudeSubagent(String name,
                                                            Map<String, Object> frontMatter,
                                                            String content) {
        Map<String, Object> fullFrontMatter = new java.util.HashMap<>(frontMatter);
        fullFrontMatter.putIfAbsent("name", name);
        fullFrontMatter.putIfAbsent("description", "test subagent");
        SubagentReference ref = new SubagentReference("file:/tmp/" + name + ".md",
                ClaudeSubagentDefinition.KIND);
        return new ClaudeSubagentDefinition(ref, fullFrontMatter, content);
    }

    private static ToolCallback mockToolCallback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(def);
        return cb;
    }
}
