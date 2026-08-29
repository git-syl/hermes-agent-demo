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
 * {@link SubagentToolCallingManager} 的旁路观测 + HITL gate 回归。HITL 路径与主 agent
 * {@link ObservableToolCallingManagerHitlTest} 逐项对应，差异仅在事件类型（{@code subagent_*}）
 * 与每条事件携带子代理 {@code name}。
 *
 * <p>命中 {@code chat.hitl.required-tools} 的工具 → emit {@code subagent_approval_request} →
 * 阻塞等 {@code POST /chat/approval} 回填（共用同一 {@link ApprovalRegistry}）；DECLINE/超时合成话术。
 */
class SubagentToolCallingManagerTest {

	private static final String SUBAGENT_NAME = "code-reviewer";

	private final ToolCallingManager delegate = mock(ToolCallingManager.class);

	// multicast：允许测试线程订阅（捕获事件）+ manager 线程发布；replay all 让 BeforeEach 订阅
	// 之后无论 emit 发生在何时都能被 events 列表收下，避免时序假设。
	private final Sinks.Many<ChatEvent> sink = Sinks.many().replay().all();

	private final ApprovalRegistry registry = new ApprovalRegistry();

	private final CopyOnWriteArrayList<ChatEvent> events = new CopyOnWriteArrayList<>();

	@BeforeEach
	void subscribeSink() {
		sink.asFlux().subscribe(events::add);
	}

	@Test
	void noPolicyMatchPassesThrough() {
		// 工具不在白名单 → 直通 delegate，零审批副作用
		ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2)); // 空白名单
		ChatResponse response = responseWith(toolCall("1", "getTime"));
		Prompt prompt = prompt();
		ToolExecutionResult expected = delegateResultFor(toolCall("1", "getTime"));
		when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

		ToolExecutionResult result = managerWith(policy).executeToolCalls(prompt, response);

		assertThat(result).isSameAs(expected);
		verify(delegate).executeToolCalls(prompt, response);
		assertThat(registry.pendingCount()).isZero();
		// SSE：subagent_tool_call + subagent_tool_result，无 subagent_approval_request
		assertThat(typesOf(events)).containsExactly("subagent_tool_call", "subagent_tool_result");
		// 事件携带子代理名
		assertThat(events).allSatisfy(e -> assertThat(e.name()).isEqualTo(SUBAGENT_NAME));
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
			.containsExactly("subagent_tool_call", "subagent_approval_request", "heartbeat", "subagent_tool_result");
		// approval_request 事件携带子代理名 + requestId(data) + 被审工具(toolCalls[0])
		ChatEvent approval = events.stream()
			.filter(e -> "subagent_approval_request".equals(e.type()))
			.findFirst()
			.orElseThrow();
		assertThat(approval.name()).isEqualTo(SUBAGENT_NAME);
		assertThat(approval.data()).isNotNull(); // requestId
		assertThat(approval.toolCalls()).hasSize(1);
		assertThat(approval.toolCalls().get(0).name()).isEqualTo("writeFile");
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
		// 关键文案：模型必须知道"用户拒了"且"别再调"，否则强工具循环模型会原样重发
		assertThat(resp.responseData()).contains("declined to approve")
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
		assertThat(elapsedMs).isBetween(100L, 5_000L);

		ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory().getLast();
		assertThat(trm.getResponses().get(0).responseData()).contains("declined to approve");
		// 审批请求确实推过前端
		assertThat(typesOf(events)).contains("subagent_approval_request");
	}

	@Test
	void mixedBatchPreservesOriginalOrder() {
		// assistant 一次发 3 个工具：需审批(拒)、不需审批(执行)、需审批(批)
		ToolCall t1 = toolCall("1", "writeFile"); // 需审批 → DECLINE
		ToolCall t2 = toolCall("2", "getTime"); // 不需审批 → 正常执行
		ToolCall t3 = toolCall("3", "bash"); // 需审批 → APPROVE
		ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile", "bash");
		ChatResponse response = responseWith(t1, t2, t3);
		Prompt prompt = prompt();

		// delegate 只会收到被批准的子集 [getTime, bash]，按这个子集返回对应 responses
		when(delegate.executeToolCalls(eq(prompt), any(ChatResponse.class))).thenAnswer(inv -> {
			ChatResponse cr = inv.getArgument(1);
			List<ToolCall> approved = cr.getResults().get(0).getOutput().getToolCalls();
			assertThat(approved).extracting(ToolCall::name).containsExactly("getTime", "bash");
			return delegateResultFor(approved.toArray(new ToolCall[0]));
		});

		CompletableFuture<Void> worker = approveByToolName(
				Map.of("writeFile", Decision.DECLINE, "bash", Decision.APPROVE), 2);

		ToolExecutionResult result = managerWith(policy).executeToolCalls(prompt, response);
		worker.join();

		// ★ 顺序保留：assistant.tool_calls 是 [1,2,3]，tool responses 必须也是 [1,2,3]
		ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory().getLast();
		assertThat(trm.getResponses()).hasSize(3);
		assertThat(trm.getResponses()).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("1", "2", "3");
		assertThat(trm.getResponses().get(0).responseData()).contains("declined to approve");
		assertThat(trm.getResponses().get(1).responseData()).isEqualTo("OK:getTime");
		assertThat(trm.getResponses().get(2).responseData()).isEqualTo("OK:bash");
	}

	@Test
	void bypassApprovalSkipsGateButKeepsExecution() {
		// 工具命中白名单，但 bypassApproval=true → 不发 subagent_approval_request、不等回填、直接执行
		ToolPolicyProperties policy = policyWith(Duration.ofSeconds(2), "writeFile");
		ChatResponse response = responseWith(toolCall("1", "writeFile"));
		Prompt prompt = prompt();
		ToolExecutionResult expected = delegateResultFor(toolCall("1", "writeFile"));
		when(delegate.executeToolCalls(prompt, response)).thenReturn(expected);

		// 关键：不启动 approveByToolName worker。若 manager 还在等审批，会卡到 timeout。
		long start = System.nanoTime();
		ToolExecutionResult result = managerWith(policy, /*bypassApproval=*/true).executeToolCalls(prompt, response);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		assertThat(result).isSameAs(expected);
		verify(delegate).executeToolCalls(prompt, response);
		// 没有 subagent_approval_request，只有 subagent_tool_call + subagent_tool_result
		assertThat(typesOf(events)).containsExactly("subagent_tool_call", "subagent_tool_result");
		assertThat(registry.pendingCount()).isZero();
		// 执行瞬时，证明真正绕过了等待
		assertThat(elapsedMs).isLessThan(1_000L);
	}

	@Test
	void delegatesResolveToolDefinitions() {
		// resolveToolDefinitions 应完全透传，不拦截
		ToolCallingChatOptions opts = ToolCallingChatOptions.builder().build();
		when(delegate.resolveToolDefinitions(opts)).thenReturn(List.of());

		managerWith(policyWith(Duration.ofSeconds(2))).resolveToolDefinitions(opts);

		verify(delegate).resolveToolDefinitions(opts);
	}

	// -------- helpers --------

	private SubagentToolCallingManager managerWith(ToolPolicyProperties policy) {
		return managerWith(policy, false);
	}

	private SubagentToolCallingManager managerWith(ToolPolicyProperties policy, boolean bypassApproval) {
		return new SubagentToolCallingManager(delegate, sink, SUBAGENT_NAME, policy, registry, bypassApproval);
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
	 * delegate 的"成功执行"返回值：每个 toolCall 对应一条 {@code OK:<name>} response。 历史:
	 * user + assistant + tool_response，与 Spring AI 真实 manager 的输出形状一致。
	 */
	private static ToolExecutionResult delegateResultFor(ToolCall... toolCalls) {
		AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(toolCalls)).build();
		List<ToolResponseMessage.ToolResponse> responses = List.of(toolCalls)
			.stream()
			.map(tc -> new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "OK:" + tc.name()))
			.toList();
		List<Message> history = List.of(new UserMessage("do it"), assistant,
				ToolResponseMessage.builder().responses(responses).build());
		return ToolExecutionResult.builder().conversationHistory(history).build();
	}

	/**
	 * 后台 worker：盯着 {@link #events}，等够 {@code expectedCount} 条
	 * {@code subagent_approval_request} 后，按 {@code rules}（toolName → decision）回填。 以 SSE 事件
	 * 为真值源，不依赖 registry 内部 map 的迭代顺序。
	 */
	private CompletableFuture<Void> approveByToolName(Map<String, Decision> rules, int expectedCount) {
		return CompletableFuture.runAsync(() -> {
			try {
				long deadlineNanos = System.nanoTime() + 5_000_000_000L; // 5s
				while (countApprovalRequests() < expectedCount && System.nanoTime() < deadlineNanos) {
					Thread.sleep(5);
				}
				for (ChatEvent e : events) {
					if (!"subagent_approval_request".equals(e.type()))
						continue;
					String toolName = e.toolCalls().get(0).name();
					Decision d = rules.getOrDefault(toolName, Decision.DECLINE);
					registry.complete(e.data(), d);
				}
			}
			catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private long countApprovalRequests() {
		return events.stream().filter(e -> "subagent_approval_request".equals(e.type())).count();
	}

	private static List<String> typesOf(List<ChatEvent> es) {
		return es.stream().map(ChatEvent::type).toList();
	}

}
