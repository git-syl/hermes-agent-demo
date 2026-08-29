package com.example.chat.service;

import com.example.chat.api.dto.ChatRequest;
import com.example.chat.api.dto.ChatRequestContext;
import com.example.chat.artifact.ArtifactStorage;
import com.example.chat.config.ModelRouter;
import com.example.chat.config.SandboxProperties;
import com.example.chat.config.SystemPromptComposer;
import com.example.chat.config.ToolPolicyProperties;
import com.example.chat.sandbox.SandboxSessionManager;
import com.example.chat.tools.BuiltinTools;
import com.example.chat.tools.FinalAnswerTool;
import com.example.chat.tools.external.ExternalToolPlatformRegistry;
import io.github.markpollack.sandbox.Sandbox;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatService#buildTaskTool} 回归：验证装配的是 {@code Task}/{@code TaskOutput} 配对工具且
 * 共用同一 {@code TaskRepository}，以及 {@code includeClaudeBuiltinSubagents} 开关——true 走库
 * build()（追加 4 内置）、false 走 fork 组装（仅用户子代理，方案 A）。
 *
 * <p>背景见 {@code other-docs/bug-taskoutput-not-registered.md}（配对注册）与
 * {@code other-docs/include-builtin-subagents-toggle.md}（方案 A：禁用 4 个 Claude 内置）。
 *
 * <p>风格对齐 {@link SandboxSubagentExecutorTest}：纯 JUnit5 + Mockito 静态 mock，无 Spring 上下文。
 */
class ChatServiceTest {

	private static final String TASK_CALL_JSON = """
			{"description":"d","prompt":"do it","subagent_type":"test-agent","run_in_background":true}""";

	@TempDir
	Path tempDir;

	/**
	 * 修复核心断言：{@code buildTaskTool} 产出恰好两个 callback，名为 {@code Task} 与
	 * {@code TaskOutput}（过去只有 Task）。
	 */
	@Test
	void buildTaskToolReturnsTaskAndTaskOutputCallbacks() {
		ToolCallback[] callbacks = buildTaskToolWithTestAgent(true, mock(Sandbox.class));

		assertThat(callbacks).isNotNull();
		assertThat(callbacks).hasSize(2);
		assertThat(callbacks).extracting(cb -> cb.getToolDefinition().name())
				.containsExactlyInAnyOrder("Task", "TaskOutput");
	}

	/**
	 * 行为端到端：后台调 Task 拿到 {@code task_id}，再用 TaskOutput 取回 —— 单条调用链同时证明
	 * (a) TaskOutput 已注册可调，(b) 两个 callback 共用同一个 TaskRepository（否则 TaskOutput
	 * 去 repo B 查不到 Task 写进 repo A 的 task_id → 返回 "No background task found"）。
	 * 后端 mock 的子代理同步返回 {@code "BG RESULT"}。走 includeClaudeBuiltin=true（库 build()）路径。
	 */
	@Test
	void taskAndTaskOutputShareRepositoryEndToEnd() {
		ToolCallback[] callbacks = buildTaskToolWithTestAgent(true, mock(Sandbox.class));
		ToolCallback taskTool = toolNamed(callbacks, "Task");
		ToolCallback taskOutputTool = toolNamed(callbacks, "TaskOutput");

		// 后台 Task：返回 task_id 并指示模型调 TaskOutput
		String taskResp = taskTool.call(TASK_CALL_JSON);
		assertThat(taskResp).contains("task_id: task_");
		String taskId = extractTaskId(taskResp);
		assertThat(taskId).startsWith("task_");

		// TaskOutput 取回：应命中共享 repo 里那条后台任务
		String outputResp = taskOutputTool.call(
				"{\"task_id\":\"" + taskId + "\",\"block\":true,\"timeout\":2000}");
		assertThat(outputResp).contains("BG RESULT");
		assertThat(outputResp).doesNotContain("No background task found");
	}

	/** guard：没传 subagents 且 includeClaudeBuiltin=false → 不装配（两者皆空才 return null）。 */
	@Test
	void buildTaskToolReturnsNullWhenNoSubagentsAndBuiltinDisabled() {
		ChatService service = newChatService(mock(ModelRouter.class), mock(AgentCacheService.class));
		ChatRequest req = baseRequest(null, false);
		ChatRequestContext ctx = ChatRequestContext.of(req, null);

		ToolCallback[] callbacks = service.buildTaskTool(ctx, mock(Sandbox.class), List.of(), null, null, null);

		assertThat(callbacks).isNull();
	}

	/**
	 * 用户 subagents 单独（无 skills、未显式开 Claude 模式）→ 不建沙箱，但仍装配 Task（fork 路径，
	 * 子代理工具集为空、不叠加 4 内置）。与 toggle 文档 §9 解耦后语义一致。
	 */
	@Test
	void buildTaskToolAssemblesWithEmptyToolsWhenSandboxNullAndUserSubagents() {
		ToolCallback[] callbacks = buildTaskToolWithTestAgent(null, null);
		assertThat(callbacks).isNotNull();
		assertThat(callbacks).hasSize(2);
		assertThat(callbacks).extracting(cb -> cb.getToolDefinition().name()).containsExactlyInAnyOrder("Task", "TaskOutput");
		// 无沙箱 → fork 路径，Task 描述只含用户 test-agent，不含 4 内置
		String desc = toolNamed(callbacks, "Task").getToolDefinition().description();
		assertThat(desc).contains("test-agent");
		assertThat(desc).doesNotContain("general-purpose", "Explore", "Plan", "Bash");
	}

	// -------- needSandbox（沙箱与 skills 解耦）--------

	@Test
	void needSandboxTrueWhenSkillsPresent() {
		ChatRequest req = baseRequest(null, null);
		assertThat(ChatService.needSandbox(req, List.of(new FileSystemResource(tempDir.toFile())))).isTrue();
	}

	@Test
	void needSandboxFalseWhenUserSubagentsOnly() {
		ChatRequest req = baseRequest(List.of(new ChatRequest.SubagentRef("a", "http://x/a.md")), null);
		assertThat(ChatService.needSandbox(req, List.of())).isFalse();
	}

	@Test
	void needSandboxTrueWhenExplicitBuiltin() {
		ChatRequest req = baseRequest(null, true);
		assertThat(ChatService.needSandbox(req, List.of())).isTrue();
	}

	@Test
	void needSandboxFalseWhenAllAbsentAndBuiltinNull() {
		ChatRequest req = baseRequest(null, null);
		assertThat(ChatService.needSandbox(req, List.of())).isFalse();
	}

	@Test
	void needSandboxFalseWhenAllAbsentAndBuiltinFalse() {
		ChatRequest req = baseRequest(null, false);
		assertThat(ChatService.needSandbox(req, List.of())).isFalse();
	}

	/**
	 * includeClaudeBuiltin 默认 true + 空 subagents + sandbox → 仅装 4 个内置（"独立可用"）。
	 * 库 {@code TaskTool.Builder.build()} 在空 refs 下仍追加 4 内置，description 里能看到它们。
	 */
	@Test
	void buildTaskToolReturnsOnlyBuiltinsWhenNoSubagentsButBuiltinEnabled() {
		ModelRouter modelRouter = mock(ModelRouter.class);
		// builder 必须在 when(...) 之外构造好 —— mockChatClientBuilderSync 内部会再开 when(...)，
		// 塞进 thenReturn(...) 里会触发 Mockito 的 UnfinishedStubbing。
		ChatClient.Builder subagentBuilder = mockChatClientBuilderSync("ignored");
		when(modelRouter.chatClientBuilder(anyString())).thenReturn(subagentBuilder);
		ChatService service = newChatService(modelRouter, mock(AgentCacheService.class));
		ChatRequest req = baseRequest(null);   // toggle null → true，无用户子代理
		ChatRequestContext ctx = ChatRequestContext.of(req, null);

		ToolCallback[] callbacks = service.buildTaskTool(ctx, mock(Sandbox.class), List.of(), null, null, null);

		assertThat(callbacks).isNotNull();
		assertThat(callbacks).hasSize(2);
		String desc = toolNamed(callbacks, "Task").getToolDefinition().description();
		assertThat(desc).contains("general-purpose", "Explore", "Plan", "Bash");
	}

	/**
	 * 方案 A 核心：{@code includeClaudeBuiltin=false} 走 fork 组装，{@code Task} 工具描述里
	 * <strong>只有</strong>用户声明的 test-agent，库那 4 个内置（general-purpose/Explore/Plan/Bash）
	 * 既看不到也调不到——硬调 Explore 会撞 "No subagent found"。
	 */
	@Test
	void forkAssembleOmitsBuiltinsWhenDisabled() {
		ToolCallback[] callbacks = buildTaskToolWithTestAgent(false, mock(Sandbox.class));
		assertThat(callbacks).isNotNull();
		assertThat(callbacks).hasSize(2);

		String desc = toolNamed(callbacks, "Task").getToolDefinition().description();
		assertThat(desc).contains("test-agent");
		assertThat(desc).doesNotContain("general-purpose", "Explore", "Plan", "Bash");

		// 硬调未注册的 Explore → TaskFunction 抛 "No subagent found"（无论 Spring AI 是否包装，根因含此消息）
		ToolCallback taskTool = toolNamed(callbacks, "Task");
		assertThatThrownBy(() -> taskTool.call(
				"{\"description\":\"d\",\"prompt\":\"p\",\"subagent_type\":\"Explore\"}"))
				.hasRootCauseMessage("No subagent found with name: Explore");
	}

	/**
	 * 回归：MCP 工具与沙箱无关，与主 agent 同判定 {@code if (mcpCallbacks.length > 0)} 即下发到子代理。
	 * 修复前 {@code buildTaskTool} 只装沙箱工具，mcpCallbacks 从未透传 → 子代理看不到 maps_weather 这类
	 * MCP 工具。这里用内置 general-purpose（frontmatter 无 tools 白名单 → 继承全部工具）+ 同步 Task 触发
	 * createTaskChatClient，断言 builder.defaultTools(...) 注册的工具里含 maps_weather。
	 */
	@Test
	void buildTaskToolInjectsMcpCallbacksIntoSubagentTools() {
		ToolCallback mcpWeather = mockToolCallbackNamed("maps_weather");

		ModelRouter modelRouter = mock(ModelRouter.class);
		// builder 必须在 when(...) 之外构造好 —— mockChatClientBuilderSync 内部会再开 when(...)，
		// 塞进 thenReturn(...) 里会触发 Mockito 的 UnfinishedStubbing。
		ChatClient.Builder subagentBuilder = mockChatClientBuilderSync("OK");
		when(modelRouter.chatClientBuilder(anyString())).thenReturn(subagentBuilder);
		when(modelRouter.providerOf(anyString())).thenReturn(ModelRouter.Provider.OPENAI);
		ChatService service = newChatService(modelRouter, mock(AgentCacheService.class));
		ChatRequest req = baseRequest(null);   // 无用户子代理；toggle null → true
		ChatRequestContext ctx = ChatRequestContext.of(req, null);

		ToolCallback[] callbacks = service.buildTaskTool(
				ctx, mock(Sandbox.class), List.of(), null, new ToolCallback[]{ mcpWeather }, null);
		ToolCallback taskTool = toolNamed(callbacks, "Task");

		// 同步 Task（run_in_background 缺省 → null → 同步路径）触发 createTaskChatClient → builder.defaultTools(...)
		taskTool.call("{\"description\":\"d\",\"prompt\":\"do it\",\"subagent_type\":\"general-purpose\"}");

		// defaultTools(Object...) varargs + (Object) cast 传入 → 容器 Object[] 长度 1，[0] 是真正的 ToolCallback[]。
		// 兜底分支应对 Mockito 版本差异（少数版本直接给 ToolCallback[] 而非容器）。
		ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
		verify(subagentBuilder).defaultTools(captor.capture());
		Object[] captured = captor.getValue();
		ToolCallback[] passedTools = (captured.length > 0 && captured[0] instanceof ToolCallback[])
				? (ToolCallback[]) captured[0] : (ToolCallback[]) captured;
		assertThat(passedTools).extracting(tc -> tc.getToolDefinition().name())
				.contains("maps_weather");
	}

	// -------- helpers --------

	/**
	 * 装配 Task/TaskOutput 配对工具：真实库 builder + mock 的 ChatClient（子代理同步返回 BG RESULT）。
	 * {@code includeBuiltin}=true 走库 build()（追加 4 内置），false 走 fork 组装（仅 test-agent）。
	 */
	private ToolCallback[] buildTaskToolWithTestAgent(Boolean includeBuiltin, Sandbox sandbox) {
		Path agentMd;
		try {
			agentMd = tempDir.resolve("test-agent.md");
			Files.writeString(agentMd, """
					---
					name: test-agent
					description: t
					---
					You are a test agent.""");
		}
		catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}

		AgentCacheService agentCache = mock(AgentCacheService.class);
		// resolve( Long, Long, String, List<SubagentRef> ) —— 全用 matcher（Mockito 规则：用了 matcher 就全用）。
		when(agentCache.resolve(any(), any(), anyString(), anyList()))
				.thenReturn(List.of(new FileSystemResource(agentMd.toFile())));

		ModelRouter modelRouter = mock(ModelRouter.class);
		when(modelRouter.providerOf(anyString())).thenReturn(ModelRouter.Provider.OPENAI);
		// 注意：builder 必须先在 when(...) 之外构造好 —— 在 thenReturn(...) 里直接调
		// mockChatClientBuilderSync 会触发 Mockito 的 UnfinishedStubbing（外层 when 还没 thenReturn，
		// 内层就在新 mock 上 stub）。
		ChatClient.Builder subagentBuilder = mockChatClientBuilderSync("BG RESULT");
		when(modelRouter.chatClientBuilder(anyString())).thenReturn(subagentBuilder);

		ChatService service = newChatService(modelRouter, agentCache);
		ChatRequest req = baseRequest(
				List.of(new ChatRequest.SubagentRef("test-agent", "http://x/test-agent.md")),
				includeBuiltin);
		ChatRequestContext ctx = ChatRequestContext.of(req, null);

		return service.buildTaskTool(ctx, sandbox, List.of(), null, null, null);
	}

	/** 构造 ChatService：只把 buildTaskTool 真正用到的协作方装实，其余 mock。 */
	private ChatService newChatService(ModelRouter modelRouter, AgentCacheService agentCache) {
		SandboxProperties sandboxProps = mock(SandboxProperties.class);
		when(sandboxProps.getMode()).thenReturn(SandboxProperties.Mode.LOCAL);
		when(sandboxProps.getExecTimeoutMs()).thenReturn(0L);

		return new ChatService(modelRouter, mock(BuiltinTools.class), mock(SkillCacheService.class),
				agentCache, mock(DynamicMcpClientFactory.class), mock(org.springframework.ai.chat.memory.ChatMemory.class),
				mock(SandboxSessionManager.class), sandboxProps, mock(ArtifactStorage.class),
				mock(SystemPromptComposer.class), mock(ExternalToolPlatformRegistry.class),
				mock(FinalAnswerTool.class), null, null, mock(TodoWriteTool.class), new ToolPolicyProperties(),
				new ApprovalRegistry());
	}

	/** 19 参 record：只填 subagents / modelName / query / includeClaudeBuiltin，其余 null。 */
	private ChatRequest baseRequest(List<ChatRequest.SubagentRef> subagents) {
		return baseRequest(subagents, null);   // null → toggle 默认 true
	}

	private ChatRequest baseRequest(List<ChatRequest.SubagentRef> subagents, Boolean includeBuiltin) {
		return new ChatRequest(null, null, null, subagents, null, null, null, null, null, "gpt-test", "q",
				null, null, null, null, null, null, null, includeBuiltin);
	}

	/** mock ChatClient.Builder → ChatClient → prompt → system/user → call → content(sync)。 */
	@SuppressWarnings("unchecked")
	private static ChatClient.Builder mockChatClientBuilderSync(String content) {
		ChatClient chatClient = mock(ChatClient.class);
		ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
		ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
		when(chatClient.prompt()).thenReturn(requestSpec);
		when(requestSpec.system(anyString())).thenReturn(requestSpec);
		when(requestSpec.user(anyString())).thenReturn(requestSpec);
		when(requestSpec.call()).thenReturn(callResponseSpec);
		when(callResponseSpec.content()).thenReturn(content);

		ChatClient.Builder builder = mock(ChatClient.Builder.class);
		when(builder.clone()).thenReturn(builder);
		when(builder.build()).thenReturn(chatClient);
		// createTaskChatClient 对 defaultOptions/defaultTools/defaultAdvisors 的返回值是丢弃的
		// （语句调用），这里显式 stub defaultTools/defaultAdvisors 避免链上 NPE；defaultOptions 的
		// 返回同样被丢弃，未 stub 也只会返回 null，不 NPE。
		when(builder.defaultTools(any(Object[].class))).thenReturn(builder);
		when(builder.defaultAdvisors(any(List.class))).thenReturn(builder);
		return builder;
	}

	private static ToolCallback toolNamed(ToolCallback[] callbacks, String name) {
		for (ToolCallback cb : callbacks) {
			if (name.equals(cb.getToolDefinition().name())) {
				return cb;
			}
		}
		throw new AssertionError("no callback named " + name);
	}

	/** 构造一个名为 {@code name} 的 mock {@link ToolCallback}（getToolDefinition().name() 返回 name）。 */
	private static ToolCallback mockToolCallbackNamed(String name) {
		ToolCallback cb = mock(ToolCallback.class);
		ToolDefinition def = mock(ToolDefinition.class);
		when(def.name()).thenReturn(name);
		when(cb.getToolDefinition()).thenReturn(def);
		return cb;
	}

	private static String extractTaskId(String taskResp) {
		Matcher m = Pattern.compile("task_[0-9a-fA-F-]+").matcher(taskResp);
		assertThat(m.find()).as("task_id not found in: " + taskResp).isTrue();
		return m.group();
	}
}
