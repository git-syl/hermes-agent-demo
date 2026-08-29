package com.example.chat.service;

import com.example.chat.api.dto.ChatEvent;
import com.example.chat.config.ToolPolicyProperties;
import com.example.chat.service.ApprovalRegistry.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HitlToolCallingGate} 直接单测 —— <b>同一份 gate 逻辑跑两遍</b>：主 agent 风格
 * （{@link HitlEventFactory#mainAgent()}，事件 {@code name}=null）与子代理风格
 * （{@link HitlEventFactory#subagent(String)}，事件 {@code name}=子代理名）。两条风格的适配器装配
 * 已由 {@link ObservableToolCallingManagerHitlTest} / {@link SubagentToolCallingManagerTest} 覆盖，
 * 本类聚焦 gate 自身的 HITL 不变式，并把<b>心跳覆盖补到两种风格</b>（此前只在主 agent 测试里有）。
 *
 * <p>测试都用 SSE 审批事件本身作为 "requestId → tool" 的真值源，不依赖 {@link ApprovalRegistry}
 * 内部 map 的迭代顺序。
 */
class HitlToolCallingGateTest {

    /** 一次风格：factory + 该风格下三类事件的 type 串 + 事件 name 字段期望值。 */
    record Style(HitlEventFactory factory, String approvalType, String toolCallType,
                 String toolResultType, String name) {}

    static Stream<Style> styles() {
        return Stream.of(
                new Style(HitlEventFactory.mainAgent(),
                        "approval_request", "tool_call", "tool_result", null),
                new Style(HitlEventFactory.subagent("code-reviewer"),
                        "subagent_approval_request", "subagent_tool_call", "subagent_tool_result", "code-reviewer"));
    }

    private final ToolCallingManager delegate = mock(ToolCallingManager.class);
    private final ApprovalRegistry registry = new ApprovalRegistry();
    // replay all：BeforeEach 订阅之后无论 emit 发生在何时都能被 events 收下，避免时序假设。
    private final Sinks.Many<ChatEvent> sink = Sinks.many().replay().all();
    private final CopyOnWriteArrayList<ChatEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void subscribeSink() {
        sink.asFlux().subscribe(events::add);
    }

    @ParameterizedTest
    @MethodSource("styles")
    void noPolicyMatchPassesThrough(Style style) {
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2)); // 空白名单
        ChatResponse response = responseWith(toolCall("1", "getTime"));
        Prompt prompt = prompt();
        ToolExecutionResult expected = delegateResultFor(toolCall("1", "getTime"));
        when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

        ToolExecutionResult result = gateWith(style, policy).executeToolCalls(prompt, response, delegate);

        assertThat(result).isSameAs(expected);
        verify(delegate).executeToolCalls(prompt, response);
        assertThat(registry.pendingCount()).isZero();
        // 只有 tool_call + tool_result，无审批请求
        assertThat(typesOf(events)).containsExactly(style.toolCallType(), style.toolResultType());
        // 事件 name 字段：主=null，子=subagentName
        assertThat(events).allSatisfy(e -> assertThat(e.name()).isEqualTo(style.name()));
    }

    @ParameterizedTest
    @MethodSource("styles")
    void singleToolApprovedRunsViaDelegate(Style style) {
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile");
        ChatResponse response = responseWith(toolCall("1", "writeFile"));
        Prompt prompt = prompt();
        ToolExecutionResult expected = delegateResultFor(toolCall("1", "writeFile"));
        when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

        CompletableFuture<Void> worker = approveByToolName(style, Map.of("writeFile", Decision.APPROVE), 1);

        ToolExecutionResult result = gateWith(style, policy).executeToolCalls(prompt, response, delegate);
        worker.join();

        assertThat(result).isSameAs(expected);
        verify(delegate).executeToolCalls(prompt, response);
        assertThat(typesOf(events))
                .containsExactly(style.toolCallType(), style.approvalType(), "heartbeat", style.toolResultType());
        // 审批事件携带 name + requestId(data) + 被审工具(toolCalls[0])
        ChatEvent approval = events.stream()
                .filter(e -> style.approvalType().equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertThat(approval.name()).isEqualTo(style.name());
        assertThat(approval.data()).isNotNull();
        assertThat(approval.toolCalls()).hasSize(1);
        assertThat(approval.toolCalls().get(0).name()).isEqualTo("writeFile");
    }

    @ParameterizedTest
    @MethodSource("styles")
    void singleToolDeclinedSynthesizesResponse(Style style) {
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile");
        ChatResponse response = responseWith(toolCall("1", "writeFile"));
        Prompt prompt = prompt();

        CompletableFuture<Void> worker = approveByToolName(style, Map.of("writeFile", Decision.DECLINE), 1);

        ToolExecutionResult result = gateWith(style, policy).executeToolCalls(prompt, response, delegate);
        worker.join();

        // 被拒工具：delegate 一次都没被调到
        verify(delegate, never()).executeToolCalls(any(), any());

        List<Message> hist = result.conversationHistory();
        assertThat(hist).hasSize(3); // user + assistant + tool
        ToolResponseMessage trm = (ToolResponseMessage) hist.get(hist.size() - 1);
        assertThat(trm.getResponses()).hasSize(1);
        ToolResponseMessage.ToolResponse resp = trm.getResponses().get(0);
        assertThat(resp.id()).isEqualTo("1");
        assertThat(resp.name()).isEqualTo("writeFile");
        // 关键文案：模型必须知道"用户拒了"且"别再调"，否则强工具循环模型会原样重发
        assertThat(resp.responseData())
                .contains("declined to approve")
                .contains("NOT executed")
                .contains("Do not call 'writeFile' again");
    }

    @ParameterizedTest
    @MethodSource("styles")
    void singleToolTimeoutFallsBackToDecline(Style style) {
        // 不启动 worker，让 future 自然超时
        ToolPolicyProperties policy = policyWith(Duration.ofMillis(150), "writeFile");
        ChatResponse response = responseWith(toolCall("1", "writeFile"));
        Prompt prompt = prompt();

        long start = System.nanoTime();
        ToolExecutionResult result = gateWith(style, policy).executeToolCalls(prompt, response, delegate);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // 超时按 DECLINE 兜底 —— delegate 一次都不会调
        verify(delegate, never()).executeToolCalls(any(), any());
        assertThat(elapsedMs).isBetween(100L, 5_000L);

        ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory().getLast();
        assertThat(trm.getResponses().get(0).responseData()).contains("declined to approve");
        // 审批请求确实推过前端
        assertThat(typesOf(events)).contains(style.approvalType);
    }

    @ParameterizedTest
    @MethodSource("styles")
    void mixedBatchPreservesOriginalOrder(Style style) {
        // assistant 一次发 3 个工具：需审批(拒)、不需审批(执行)、需审批(批)
        ToolCall t1 = toolCall("1", "writeFile"); // 需审批 → DECLINE
        ToolCall t2 = toolCall("2", "getTime");   // 不需审批 → 正常执行
        ToolCall t3 = toolCall("3", "bash");      // 需审批 → APPROVE
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile", "bash");
        ChatResponse response = responseWith(t1, t2, t3);
        Prompt prompt = prompt();

        // delegate 只会收到被批准的子集 [getTime, bash]，按这个子集返回对应 responses
        when(delegate.executeToolCalls(eq(prompt), any(ChatResponse.class)))
                .thenAnswer(inv -> {
                    ChatResponse cr = inv.getArgument(1);
                    List<ToolCall> approved = cr.getResults().get(0).getOutput().getToolCalls();
                    assertThat(approved).extracting(ToolCall::name).containsExactly("getTime", "bash");
                    return delegateResultFor(approved.toArray(new ToolCall[0]));
                });

        CompletableFuture<Void> worker = approveByToolName(style,
                Map.of("writeFile", Decision.DECLINE, "bash", Decision.APPROVE), 2);

        ToolExecutionResult result = gateWith(style, policy).executeToolCalls(prompt, response, delegate);
        worker.join();

        // ★ 顺序保留：assistant.tool_calls 是 [1,2,3]，tool responses 必须也是 [1,2,3]
        ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory().getLast();
        assertThat(trm.getResponses()).hasSize(3);
        assertThat(trm.getResponses()).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("1", "2", "3");
        assertThat(trm.getResponses().get(0).responseData()).contains("declined to approve");
        assertThat(trm.getResponses().get(1).responseData()).isEqualTo("OK:getTime");
        assertThat(trm.getResponses().get(2).responseData()).isEqualTo("OK:bash");
    }

    @ParameterizedTest
    @MethodSource("styles")
    void bypassApprovalSkipsGateButKeepsExecution(Style style) {
        // 工具命中白名单，但 bypassApproval=true → 不发审批请求、不等回填、直接执行
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile");
        ChatResponse response = responseWith(toolCall("1", "writeFile"));
        Prompt prompt = prompt();
        ToolExecutionResult expected = delegateResultFor(toolCall("1", "writeFile"));
        when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

        // 关键：不启动 worker。如果 gate 还在等审批，会卡到 timeout。
        long start = System.nanoTime();
        ToolExecutionResult result = gateWith(style, policy, /*bypassApproval=*/true)
                .executeToolCalls(prompt, response, delegate);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(result).isSameAs(expected);
        verify(delegate).executeToolCalls(prompt, response);
        // 没有审批请求，只有正常的 tool_call + tool_result
        assertThat(typesOf(events)).containsExactly(style.toolCallType(), style.toolResultType());
        // 没有任何 future 被注册：registry 清零
        assertThat(registry.pendingCount()).isZero();
        // 执行应当是瞬时的（远低于 timeout），证明真正绕过了等待
        assertThat(elapsedMs).isLessThan(1_000L);
    }

    @ParameterizedTest
    @MethodSource("styles")
    void heartbeatEmittedImmediatelyAfterApprovalRequest(Style style) {
        // emit 审批请求后立即补一条 heartbeat 撑满代理缓冲，确保前端即时收到审批提示
        // （不再有周期性心跳 —— 那已移到 /chat/stream 流管线，见 StreamHeartbeat）。
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile");
        ChatResponse response = responseWith(toolCall("1", "writeFile"));
        Prompt prompt = prompt();
        ToolExecutionResult expected = delegateResultFor(toolCall("1", "writeFile"));
        when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

        CompletableFuture<Void> worker = approveByToolName(style, Map.of("writeFile", Decision.APPROVE), 1);

        ToolExecutionResult result = gateWith(style, policy).executeToolCalls(prompt, response, delegate);
        worker.join();

        assertThat(result).isSameAs(expected);
        // 事件序：工具调用 → 审批请求 → heartbeat(立即) → 工具结果
        assertThat(typesOf(events))
                .containsExactly(style.toolCallType(), style.approvalType(), "heartbeat", style.toolResultType());
        // 恰好一条心跳（gate 不再发周期性心跳）
        assertThat(countHeartbeats()).isEqualTo(1);
        // 裸事件不变量：心跳帧不带 name
        assertThat(events).filteredOn(e -> "heartbeat".equals(e.type()))
                .allSatisfy(e -> assertThat(e.name()).isNull());
    }

    // -------- helpers --------

    private HitlToolCallingGate gateWith(Style style, ToolPolicyProperties policy) {
        return gateWith(style, policy, false);
    }

    private HitlToolCallingGate gateWith(Style style, ToolPolicyProperties policy, boolean bypassApproval) {
        return new HitlToolCallingGate(sink, policy, registry, bypassApproval, style.factory());
    }

    private static ToolPolicyProperties policyWith(Duration timeout, String... requiredTools) {
        // 默认心跳 15s，远大于任何单测耗时 → 心跳天然不会插入到既有断言里
        return policyWith(timeout, Duration.ofSeconds(15), requiredTools);
    }

    private static ToolPolicyProperties policyWith(Duration timeout, Duration heartbeatInterval,
                                                   String... requiredTools) {
        ToolPolicyProperties p = new ToolPolicyProperties();
        p.setTimeout(timeout);
        p.setHeartbeatInterval(heartbeatInterval);
        p.setRequiredTools(Set.of(requiredTools));
        return p;
    }

    private static Prompt prompt() {
        return new Prompt(List.of(new UserMessage("do it")),
                ToolCallingChatOptions.builder().toolContext(Map.of()).build());
    }

    private static ChatResponse responseWith(ToolCall... toolCalls) {
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(toolCalls)).build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static ToolCall toolCall(String id, String name) {
        return new ToolCall(id, "function", name, "{}");
    }

    /**
     * delegate 的"成功执行"返回值：每个 toolCall 对应一条 {@code OK:<name>} response。
     * 历史: user + assistant + tool_response，与 Spring AI 真实 manager 的输出形状一致。
     */
    private static ToolExecutionResult delegateResultFor(ToolCall... toolCalls) {
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(toolCalls)).build();
        List<ToolResponseMessage.ToolResponse> responses = List.of(toolCalls).stream()
                .map(tc -> new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "OK:" + tc.name()))
                .toList();
        List<Message> history = List.of(new UserMessage("do it"), assistant,
                ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder().conversationHistory(history).build();
    }

    /**
     * 后台 worker：盯着 {@link #events}，等够 {@code expectedCount} 条该风格的审批请求事件后，
     * 按 {@code rules}（toolName → decision）回填。以 SSE 事件为真值源，不依赖 registry 内部
     * map 的迭代顺序。找不到规则的 toolName 一律按 DECLINE 处理（fail-safe）。
     */
    private CompletableFuture<Void> approveByToolName(Style style, Map<String, Decision> rules, int expectedCount) {
        return CompletableFuture.runAsync(() -> {
            try {
                long deadlineNanos = System.nanoTime() + 5_000_000_000L; // 5s
                while (countApprovals(style) < expectedCount && System.nanoTime() < deadlineNanos) {
                    Thread.sleep(5);
                }
                for (ChatEvent e : events) {
                    if (!style.approvalType().equals(e.type())) continue;
                    String toolName = e.toolCalls().get(0).name();
                    Decision d = rules.getOrDefault(toolName, Decision.DECLINE);
                    registry.complete(e.data(), d);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private long countApprovals(Style style) {
        return events.stream().filter(e -> style.approvalType().equals(e.type())).count();
    }

    private long countHeartbeats() {
        return events.stream().filter(e -> "heartbeat".equals(e.type())).count();
    }

    private static List<String> typesOf(List<ChatEvent> es) {
        return es.stream().map(ChatEvent::type).toList();
    }
}
