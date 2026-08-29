package com.example.chat.service;

import com.example.chat.api.dto.ChatEvent;
import com.example.chat.config.ToolPolicyProperties;
import com.example.chat.service.ApprovalRegistry.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ObservableToolCallingManager} 六条 HITL 主路径回归：
 *
 * <ol>
 *   <li>{@link #noPolicyMatchPassesThrough} —— 工具不在白名单，直通 delegate，零审批副作用；</li>
 *   <li>{@link #singleToolApprovedRunsViaDelegate} —— 单工具 APPROVE，正常执行；</li>
 *   <li>{@link #singleToolDeclinedSynthesizesResponse} —— 单工具 DECLINE，delegate 完全不被调用，
 *       合成话术告诉模型"用户拒了，别再调"；</li>
 *   <li>{@link #singleToolTimeoutFallsBackToDecline} —— future 超时按 DECLINE 兜底，fail-safe；</li>
 *   <li>{@link #mixedBatchPreservesOriginalOrder} —— 批量 [需审批, 不需审批, 需审批] 三工具，
 *       一拒一批，最终 ToolResponse 按原始顺序排列（顺序错位会被 DeepSeek/OpenAI 严格 400）；</li>
 *   <li>{@link #bypassApprovalSkipsGateButKeepsExecution} —— per-request {@code bypassApproval=true}
 *       让命中白名单的工具直接执行，不发 {@code approval_request}、不阻塞 future。</li>
 *   <li>{@link #awaitDecisionEmitsHeartbeatsWhileWaiting} —— 审批等待期间按 {@code heartbeatInterval}
 *       定时推 {@code heartbeat} 帧（破解 nginx/CF/ALB SSE 缓冲）；决定一到立即停发，
 *       验证 {@code Disposable.dispose()} 不漏。</li>
 * </ol>
 *
 * <p>测试都用 SSE {@code approval_request} 事件本身作为 "requestId → tool" 的真值源，
 * 而不依赖 {@code ApprovalRegistry} 内部 map 的迭代顺序，避免出现并发顺序假设。
 */
class ObservableToolCallingManagerHitlTest {

    private final ToolCallingManager delegate = mock(ToolCallingManager.class);
    private final ApprovalRegistry registry = new ApprovalRegistry();
    // multicast：允许测试线程订阅（捕获事件）+ manager 线程发布；replay all 让 BeforeEach 订阅
    // 之后无论 emit 发生在何时都能被 events 列表收下，避免时序假设。
    private final Sinks.Many<ChatEvent> sink = Sinks.many().replay().all();
    private final CopyOnWriteArrayList<ChatEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void subscribeSink() {
        sink.asFlux().subscribe(events::add);
    }

    @Test
    void noPolicyMatchPassesThrough() {
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2)); // 空白名单
        ChatResponse response = responseWith(toolCall("1", "getTime"));
        Prompt prompt = prompt();
        ToolExecutionResult expected = delegateResultFor(toolCall("1", "getTime"));
        when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

        ToolExecutionResult result = managerWith(policy).executeToolCalls(prompt, response);

        assertThat(result).isSameAs(expected);
        verify(delegate).executeToolCalls(prompt, response);
        assertThat(registry.pendingCount()).isZero();

        // SSE：只有 tool_call + tool_result，不应有 approval_request
        assertThat(typesOf(events)).containsExactly("tool_call", "tool_result");
    }

    @Test
    void singleToolApprovedRunsViaDelegate() {
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile");
        ChatResponse response = responseWith(toolCall("1", "writeFile"));
        Prompt prompt = prompt();
        ToolExecutionResult expected = delegateResultFor(toolCall("1", "writeFile"));
        when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

        CompletableFuture<Void> worker = approveByToolName(Map.of("writeFile", Decision.APPROVE), 1);

        ToolExecutionResult result = managerWith(policy).executeToolCalls(prompt, response);
        worker.join();

        assertThat(result).isSameAs(expected);
        verify(delegate).executeToolCalls(prompt, response);
        assertThat(typesOf(events))
                .containsExactly("tool_call", "approval_request", "heartbeat", "tool_result");
    }

    @Test
    void singleToolDeclinedSynthesizesResponse() {
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile");
        ChatResponse response = responseWith(toolCall("1", "writeFile"));
        Prompt prompt = prompt();

        CompletableFuture<Void> worker = approveByToolName(Map.of("writeFile", Decision.DECLINE), 1);

        ToolExecutionResult result = managerWith(policy).executeToolCalls(prompt, response);
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
        // 关键文案：模型必须知道是"用户拒了"且"别再调"，否则强工具循环模型会原样重发
        assertThat(resp.responseData())
                .contains("declined to approve")
                .contains("NOT executed")
                .contains("Do not call 'writeFile' again");
    }

    @Test
    void singleToolTimeoutFallsBackToDecline() {
        // 不启动 worker，让 future 自然超时
        ToolPolicyProperties policy = policyWith(Duration.ofMillis(150), "writeFile");
        ChatResponse response = responseWith(toolCall("1", "writeFile"));
        Prompt prompt = prompt();

        long start = System.nanoTime();
        ToolExecutionResult result = managerWith(policy).executeToolCalls(prompt, response);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        // 超时按 DECLINE 兜底 —— delegate 一次都不会调
        verify(delegate, never()).executeToolCalls(any(), any());
        // 实际耗时应该接近 timeout，但留足缓冲（CI 抖动）
        assertThat(elapsedMs).isBetween(100L, 5_000L);

        ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory().getLast();
        assertThat(trm.getResponses().get(0).responseData()).contains("declined to approve");
    }

    @Test
    void mixedBatchPreservesOriginalOrder() {
        // assistant 一次发了 3 个工具：需审批、不需审批、需审批
        ToolCall t1 = toolCall("1", "writeFile");   // 需审批 → DECLINE
        ToolCall t2 = toolCall("2", "getTime");     // 不需审批 → 正常执行
        ToolCall t3 = toolCall("3", "bash");        // 需审批 → APPROVE
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

        CompletableFuture<Void> worker = approveByToolName(
                Map.of("writeFile", Decision.DECLINE, "bash", Decision.APPROVE), 2);

        ToolExecutionResult result = managerWith(policy).executeToolCalls(prompt, response);
        worker.join();

        // ★ 顺序保留：assistant.tool_calls 是 [1, 2, 3]，所以 tool responses 必须也是 [1, 2, 3]
        //   responses[0] = writeFile 的 declined 合成话术
        //   responses[1] = getTime 的 OK
        //   responses[2] = bash 的 OK
        ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory().getLast();
        assertThat(trm.getResponses()).hasSize(3);
        assertThat(trm.getResponses()).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("1", "2", "3");
        assertThat(trm.getResponses().get(0).responseData()).contains("declined to approve");
        assertThat(trm.getResponses().get(1).responseData()).isEqualTo("OK:getTime");
        assertThat(trm.getResponses().get(2).responseData()).isEqualTo("OK:bash");
    }

    @Test
    void heartbeatEmittedImmediatelyAfterApprovalRequest() {
        // emit approval_request 后立即补一条 heartbeat 撑满代理缓冲，确保前端即时收到审批提示
        // （周期性心跳已移到 /chat/stream 流管线，gate 不再发）。
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile");
        ChatResponse response = responseWith(toolCall("1", "writeFile"));
        Prompt prompt = prompt();
        ToolExecutionResult expected = delegateResultFor(toolCall("1", "writeFile"));
        when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

        CompletableFuture<Void> worker = approveByToolName(Map.of("writeFile", Decision.APPROVE), 1);

        ToolExecutionResult result = managerWith(policy).executeToolCalls(prompt, response);
        worker.join();

        assertThat(result).isSameAs(expected);
        // 事件序：tool_call → approval_request → heartbeat(立即) → tool_result
        assertThat(typesOf(events))
                .containsExactly("tool_call", "approval_request", "heartbeat", "tool_result");
        assertThat(countHeartbeats()).isEqualTo(1);
    }

    @Test
    void bypassApprovalSkipsGateButKeepsExecution() {
        // 工具命中白名单，但请求级 bypassApproval=true → 不发 approval_request、不等回填、直接执行
        ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile");
        ChatResponse response = responseWith(toolCall("1", "writeFile"));
        Prompt prompt = prompt();
        ToolExecutionResult expected = delegateResultFor(toolCall("1", "writeFile"));
        when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

        // 关键：不启动 approveByToolName worker。如果 manager 还在等审批，这条会卡到 timeout。
        long start = System.nanoTime();
        ToolExecutionResult result = managerWith(policy, /*bypassApproval=*/true).executeToolCalls(prompt, response);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(result).isSameAs(expected);
        verify(delegate).executeToolCalls(prompt, response);
        // 没有 approval_request 事件，只有正常的 tool_call + tool_result
        assertThat(typesOf(events)).containsExactly("tool_call", "tool_result");
        // 没有任何 future 被注册：registry 清零
        assertThat(registry.pendingCount()).isZero();
        // 执行应当是瞬时的（远低于 timeout），证明真正绕过了等待
        assertThat(elapsedMs).isLessThan(1_000L);
    }

    // -------- helpers --------

    private ObservableToolCallingManager managerWith(ToolPolicyProperties policy) {
        return managerWith(policy, false);
    }

    private ObservableToolCallingManager managerWith(ToolPolicyProperties policy, boolean bypassApproval) {
        return new ObservableToolCallingManager(delegate, sink, policy, registry, bypassApproval);
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
     * 构造 delegate 的"成功执行"返回值：每个 toolCall 对应一条 {@code OK:<name>} response。
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
     * 后台 worker：盯着 {@link #events} 列表，等够 {@code expectedCount} 条
     * {@code approval_request} 事件后，按 {@code rules}（toolName → decision）回填。
     *
     * <p>此模式以 SSE 事件为真值源，<b>不依赖</b> registry 内部 map 的非确定性迭代顺序，
     * 多 pending 场景也能精准定位"哪个 requestId 对应哪个工具"，故顺序断言稳定可重复。
     *
     * <p>找不到规则的 toolName 一律按 DECLINE 处理（fail-safe；测试本意外的 toolName 不该被默默放行）。
     */
    private CompletableFuture<Void> approveByToolName(Map<String, Decision> rules, int expectedCount) {
        return CompletableFuture.runAsync(() -> {
            try {
                long deadlineNanos = System.nanoTime() + 5_000_000_000L; // 5s
                while (countApprovalRequests() < expectedCount && System.nanoTime() < deadlineNanos) {
                    Thread.sleep(5);
                }
                for (ChatEvent e : events) {
                    if (!"approval_request".equals(e.type())) continue;
                    String toolName = e.toolCalls().get(0).name();
                    Decision d = rules.getOrDefault(toolName, Decision.DECLINE);
                    registry.complete(e.data(), d);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private long countApprovalRequests() {
        return events.stream().filter(e -> "approval_request".equals(e.type())).count();
    }

    private long countHeartbeats() {
        return events.stream().filter(e -> "heartbeat".equals(e.type())).count();
    }

    private static List<String> typesOf(List<ChatEvent> es) {
        return es.stream().map(ChatEvent::type).toList();
    }
}
