package com.example.chat.service;

import com.example.chat.api.dto.ChatEvent;
import com.example.chat.api.dto.ChatRequest;
import com.example.chat.api.dto.ChatRequestContext;
import com.example.chat.api.dto.ChatResponse;
import com.example.chat.artifact.ArtifactStorage;
import com.example.chat.config.ModelRouter;
import com.example.chat.config.SandboxProperties;
import com.example.chat.config.SystemPromptComposer;
import com.example.chat.config.ToolPolicyProperties;
import com.example.chat.sandbox.SandboxArtifactTool;
import com.example.chat.sandbox.SandboxBashTool;
import com.example.chat.sandbox.SandboxFileSystemTools;
import com.example.chat.sandbox.SandboxGlobTool;
import com.example.chat.sandbox.SandboxGrepTool;
import com.example.chat.sandbox.SandboxSessionManager;
import com.example.chat.tools.BuiltinTools;
import com.example.chat.tools.FinalAnswerTool;
import com.example.chat.tools.ProxiedBraveWebSearchTool;
import com.example.chat.tools.external.ExternalToolCallback;
import com.example.chat.tools.external.ExternalToolPlatform;
import com.example.chat.tools.external.ExternalToolPlatformRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentDefinition;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentResolver;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;
import org.springaicommunity.agent.common.task.subagent.SubagentDefinition;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import io.github.markpollack.sandbox.Sandbox;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest.ReasoningEffort;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest.Thinking;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * Spring AI 官方请求/响应日志 advisor。默认 order=0，会被 ToolCallingAdvisor 包裹，
     * 因此工具循环里每一轮模型调用前后都会落日志。开关：
     * {@code logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor=DEBUG}。
     */
    private static final SimpleLoggerAdvisor LOGGER_ADVISOR = new SimpleLoggerAdvisor();

    /**
     * 单次 /chat/stream 请求的绝对总时长上限，兜住"恶意死循环 + 工具熔断"叠加可达数小时的最坏场景。
     * 必须用 {@link Mono#delay} + mergeWith 实现，不能用 {@code Flux.timeout(Duration)} ——
     * 后者是"两次 emit 之间的空闲超时"，对持续 emit 的死循环不生效。
     */
    private static final Duration MAX_STREAM_DURATION = Duration.ofMinutes(5);

    private final ModelRouter modelRouter;
    private final BuiltinTools builtinTools;
    private final SkillCacheService skillCache;
    private final AgentCacheService agentCache;
    private final DynamicMcpClientFactory mcpFactory;
    // 只有 ChatRequest.useServerMemory=true 时才会被装到 MessageChatMemoryAdvisor 里使用；
    // 默认走无状态模式，历史由请求体 history 字段提供。
    private final ChatMemory chatMemory;
    // 沙箱创建/复用统一通过 SandboxSessionManager，不再直接持有 SandboxFactory。
    private final SandboxSessionManager sandboxSessionManager;
    private final SandboxProperties sandboxProps;
    private final ArtifactStorage artifactStorage;
    private final SystemPromptComposer systemPromptComposer;
    private final ExternalToolPlatformRegistry externalToolPlatforms;
    private final FinalAnswerTool finalAnswerTool;
    // 仅当 app.brave-search.api-key 配置非空时由 BraveSearchConfig 注册，
    // 否则注入为 null —— 不注册等同于"未启用 Web 搜索"，请求中即便写了 WebSearch 也只是被忽略。
    // 注意：用本项目内的 ProxiedBraveWebSearchTool 替代上游 BraveWebSearchTool，
    // 目的是支持 base-url 可配置（绕过国内直连超时）。@Tool name 保持 "WebSearch" 不变。
    private final @Nullable ProxiedBraveWebSearchTool braveWebSearchTool;
    // 仅当 app.smart-web-fetch.enabled=true 且 DeepSeek 已配置时由 SmartWebFetchConfig 注册。
    // 内部固定绑定 deepseek-v4-flash 做网页摘要，不跟随请求级 modelName 切换。
    private final @Nullable SmartWebFetchTool smartWebFetchTool;
    // 由 TodoWriteToolConfig 永久注册（无外部依赖、无开关），handler 内部打日志 + 发
    // TodoUpdatedEvent，将来加 SSE @EventListener 即可推到前端。工具名 "TodoWrite"。
    private final TodoWriteTool todoWriteTool;
    // HITL 审批策略（绑 chat.hitl.*）+ 注册表（requestId → Future）。两者都给到流式
    // ObservableToolCallingManager；非流式 chat() 不装配 manager，因此不走 HITL。
    private final ToolPolicyProperties toolPolicy;
    private final ApprovalRegistry approvalRegistry;

    public ChatService(ModelRouter modelRouter,
                       BuiltinTools builtinTools,
                       SkillCacheService skillCache,
                       AgentCacheService agentCache,
                       DynamicMcpClientFactory mcpFactory,
                       ChatMemory chatMemory,
                       SandboxSessionManager sandboxSessionManager,
                       SandboxProperties sandboxProps,
                       ArtifactStorage artifactStorage,
                       SystemPromptComposer systemPromptComposer,
                       ExternalToolPlatformRegistry externalToolPlatforms,
                       FinalAnswerTool finalAnswerTool,
                       @Nullable ProxiedBraveWebSearchTool braveWebSearchTool,
                       @Nullable SmartWebFetchTool smartWebFetchTool,
                       TodoWriteTool todoWriteTool,
                       ToolPolicyProperties toolPolicy,
                       ApprovalRegistry approvalRegistry) {
        this.modelRouter = modelRouter;
        this.builtinTools = builtinTools;
        this.skillCache = skillCache;
        this.agentCache = agentCache;
        this.mcpFactory = mcpFactory;
        this.chatMemory = chatMemory;
        this.sandboxSessionManager = sandboxSessionManager;
        this.sandboxProps = sandboxProps;
        this.artifactStorage = artifactStorage;
        this.systemPromptComposer = systemPromptComposer;
        this.externalToolPlatforms = externalToolPlatforms;
        this.finalAnswerTool = finalAnswerTool;
        this.braveWebSearchTool = braveWebSearchTool;
        this.smartWebFetchTool = smartWebFetchTool;
        this.todoWriteTool = todoWriteTool;
        this.toolPolicy = toolPolicy;
        this.approvalRegistry = approvalRegistry;
    }

    /**
     * 同步对话入口：返回完整的 {@link ChatResponse}。任何异常按 RuntimeException 透传，由
     * Spring 默认错误处理转 4xx/5xx —— 与 {@link #streamChat} 的"SSE error 事件"协议不同，
     * 因为非流式接口本来就是请求-响应模型，HTTP 状态码语义清晰。
     *
     * <p>Skill 解析放在 {@code ChatResources.allocate} 之外（不属于需要兜底的资源）；
     * 解析抛异常时下面的 try-with-resources 不会被打开，零泄漏。
     *
     * <p>{@link SandboxSessionManager.SessionKey} 收到 {@code req.sessionId()} 原值（可能为空）
     * 而不是兜底后的 {@code ctx.sessionId()} —— 匿名/无 sessionId 请求走 EphemeralLease 不入缓存，
     * 避免 LRU 污染。
     */
    public ChatResponse chat(ChatRequestContext ctx) {
        ChatRequest req = ctx.req();
        if (req == null || !StringUtils.hasText(req.query())) {
            throw new IllegalArgumentException("query must not be empty");
        }
        String sessionId = ctx.sessionId();

        List<Resource> skillDirs = skillCache.resolve(req.userId(), req.assistantId(), sessionId, req.skills());
        boolean needSandbox = needSandbox(req, skillDirs);

        SandboxSessionManager.SessionKey sandboxSessionKey =
                SandboxSessionManager.SessionKey.of(req.userId(), req.assistantId(), req.sessionId());
        try (ChatResources resources = ChatResources.allocate(
                () -> mcpFactory.build(req.mcpConfig()),
                () -> needSandbox ? sandboxSessionManager.acquire(sandboxSessionKey, skillDirs) : null)) {
            ChatClient.ChatClientRequestSpec spec = assembleSpec(ctx, resources, skillDirs, null, null);
            org.springframework.ai.chat.model.ChatResponse raw = spec.call().chatResponse();
            ReasoningSplit split = extractReasoningAndText(raw);
            return new ChatResponse(split.text(), split.reasoning(), sessionId);
        } catch (RuntimeException e) {
            log.error("Chat failed (session={}): {}", sessionId, e.getMessage(), e);
            logUpstreamHttpBody(e);
            throw e;
        }
    }

    /**
     * /chat/stream 入口：固定输出 SSE 流（{@code text/event-stream}），任何错误都以一条
     * {@code type=error} 的 {@link ChatEvent} 形式返回，绝不让端点对外混搭 200/SSE 与 500/JSON。
     *
     * <p>用 {@link Flux#using} 把"资源分配 → 业务流构造 → 资源释放"打包成一个原子单元：
     * <ul>
     *   <li>分配阶段抛异常（如 {@link com.example.chat.sandbox.SandboxSessionManager.SandboxCapacityExceededException}、
     *       mcp 构建失败、skill 解析失败）→ 落 {@code onErrorResume}，转成 {@code ChatEvent.error}。</li>
     *   <li>业务流构造阶段抛异常时，已分配的资源由 reactor 自动 close —— 不再有原同步段的资源泄漏窗口。</li>
     *   <li>主流 complete / error / 客户端 cancel 时统一回调 {@link StreamContext#close}，等价于原 doFinally。</li>
     * </ul>
     */
    public Flux<ChatEvent> streamChat(ChatRequestContext ctx) {
        ChatRequest req = ctx.req();
        // 入参轻量校验：直接发一条 SSE error 事件，前端拿到的协议永远是 text/event-stream。
        if (req == null || !StringUtils.hasText(req.query())) {
            return Flux.just(ChatEvent.error("query must not be empty"));
        }
        String sessionId = ctx.sessionId();

        return Flux.using(
                        () -> allocateStreamResources(ctx),
                        sc -> buildStreamFlux(ctx, sc),
                        StreamContext::close)
                .onErrorResume(e -> {
                    log.error("Stream failed (session={}): {}", sessionId, e.getMessage(), e);
                    logUpstreamHttpBody(e);
                    return Flux.just(ChatEvent.error(e.getMessage()));
                });
    }

    /**
     * 同步预分配段：解析 skills、按需借用 sandbox lease、构造 mcp session。
     *
     * <p>SandboxSessionManager 按 {@code (userId, assistantId, sessionId)} 复用容器；灰度关闭或
     * {@code sessionId} 为空时回退到一次性沙箱。{@link SandboxSessionManager.SessionKey#of} 收到
     * {@code req.sessionId()} 原值（可能为空）—— 匿名/无 sessionId 请求自动走 EphemeralLease 不入缓存，
     * 避免高并发匿名场景污染 LRU。
     *
     * <p>只有至少一个 skill 解析成功（{@code skillDirs} 非空）才创建 sandbox：与 SkillsTool 的注入
     * 条件保持一致，避免"sandbox 已建但模型看不到任何 skill"的中间态。
     */
    private StreamContext allocateStreamResources(ChatRequestContext ctx) {
        ChatRequest req = ctx.req();
        List<Resource> skillDirs = skillCache.resolve(req.userId(), req.assistantId(), ctx.sessionId(), req.skills());
        boolean needSandbox = needSandbox(req, skillDirs);
        ChatResources resources = ChatResources.allocate(
                () -> mcpFactory.build(req.mcpConfig()),
                () -> needSandbox
                        ? sandboxSessionManager.acquire(
                                SandboxSessionManager.SessionKey.of(req.userId(), req.assistantId(), req.sessionId()),
                                skillDirs)
                        : null);
        return new StreamContext(resources, skillDirs);
    }

    /**
     * 用 {@link StreamContext} 里的资源拼出业务流：spec 装配交给 {@link #assembleSpec}，
     * 这里只负责工具结果旁路 sink、deadline 护栏、sink 收尾。
     * <p>资源释放和顶层错误转换 —— 由 {@link #streamChat} 的 {@code Flux.using + onErrorResume} 兜底。
     */
    private Flux<ChatEvent> buildStreamFlux(ChatRequestContext ctx, StreamContext sc) {
        // 旁路 sink：ObservableToolCallingManager 在工具执行结束后会往这里 emit tool_result，
        // HITL 命中时还会先 emit approval_request 并阻塞等 ApprovalRegistry 回填决定。
        // unicast + onBackpressureBuffer 防止工具风暴时丢事件。
        Sinks.Many<ChatEvent> toolEventSink = Sinks.many().unicast().onBackpressureBuffer();
        // bypassApproval：调用方 per-request 绕行 HITL 的开关。null 视为 false（默认走正常 gate）。
        boolean bypassApproval = Boolean.TRUE.equals(ctx.req().bypassApproval());
        ToolCallingManager observableManager = new ObservableToolCallingManager(
                ToolCallingManager.builder().build(), toolEventSink, toolPolicy, approvalRegistry, bypassApproval);

        ChatClient.ChatClientRequestSpec spec = assembleSpec(ctx, sc.resources(), sc.skillDirs(), observableManager, toolEventSink);

        // 绝对总时长 deadline：用 takeUntilOther 而不是 mergeWith。
        // 关键差异：deadline Mono 永远不会"正常 complete"（只会延时后 error），
        // mergeWith 必须等所有上游 complete 才完成 → 正常 1 秒的对话也会被卡满 deadline 才关。
        // takeUntilOther 的语义是：主流先完就把 other 取消；other 先 emit/error 就让主流 emit/error。
        // 两种正常路径都 OK，恶意死循环走 error → 顶层 onErrorResume → 外层 Flux.using 释放资源。
        Mono<Object> deadlineError = Mono.delay(MAX_STREAM_DURATION)
                .flatMap(tick -> Mono.error(new RuntimeException(
                        "Stream exceeded maximum duration of " + MAX_STREAM_DURATION.toMinutes()
                                + " minutes; aborting and releasing sandbox.")));

        // 周期性保活心跳：每 heartbeatInterval 往旁路 sink 推一条裸 heartbeat，撑满代理缓冲防止
        // HITL 长阻塞 / 模型慢响应 quiet gap 期间中间层 hold 住前序事件。HITL gate 另在 emit
        // approval_request 后立即补一条 heartbeat 确保审批提示即时送达。流结束统一 dispose。
        Disposable heartbeat = StreamHeartbeat.start(toolEventSink, toolPolicy.getHeartbeatInterval());

        return spec.stream().chatResponse()
                .concatMap(this::toEvents)
                // 上游主响应流一结束/异常，立刻 complete 旁路 sink，否则 mergeWith 会一直等 sink 完成
                // → SSE 永不关闭。FinalAnswer (returnDirect=true) 走的也是正常 complete 路径，无需特殊处理。
                .doOnComplete(toolEventSink::tryEmitComplete)
                .doOnError(e -> toolEventSink.tryEmitComplete())
                .mergeWith(toolEventSink.asFlux())
                .takeUntilOther(deadlineError)
                // 客户端断连 (CANCEL) 时上游不会触发 doOnComplete/doOnError，这里再补一刀关 sink + 停心跳。
                // 资源释放交给外层 Flux.using，本地只兜 sink + heartbeat。
                .doFinally(signal -> {
                    heartbeat.dispose();
                    toolEventSink.tryEmitComplete();
                });
    }

    /**
     * {@link #chat} 与 {@link #streamChat} 共用的 ChatClient spec 装配：模型路由、advisor 链、
     * system prompt、6 路 tool 来源（sandbox / builtin / skill / mcp / external / finalAnswer）、
     * toolContext 注入。流式与非流式只差 {@code toolCallingManager}（装饰版把工具结果 emit 进
     * 旁路 sink），其余装配完全一致。
     *
     * <p>关键约束（陷阱集合）：
     * <ul>
     *   <li>用 {@code prompt()} + {@code .user(query)} 而不是 {@code prompt(query)}：后者会把整段
     *       当 message 头注入，导致后续 {@code .messages(history)} 顺序错乱，DeepSeek 会拒
     *       "tool 角色前必须紧跟带 tool_calls 的 assistant"。</li>
     *   <li>{@code MessageChatMemoryAdvisor}（order +200）默认在 {@code ToolCallingAdvisor}
     *       （order +300）<u>外层</u>，只持久化最终的一对 user/assistant，不再把 tool 中间消息写入
     *       chat memory —— {@code conversationHistoryEnabled} 保持默认 {@code true}，让工具循环
     *       内部自己维护回放历史，否则下一轮会缺掉触发工具调用的 assistant 帧。</li>
     *   <li>{@code maxToolIterations}：2.0.1 起走官方工具限额（见 {@link #withToolCallLimit}），
     *       限的是"本 turn 工具调用总次数"；null/&le;0 不收紧。超限由 {@code ToolCallingAdvisor}
     *       优雅收尾，不再抛错打断流。</li>
     * </ul>
     *
     * @param streamingToolManager 非 null 时进入流式模式；null 时为非流式（{@code .call()} 路径）。
     *                             manager 持有调用方的旁路 sink，必须由调用方传入而非内部构造。
     */
    private ChatClient.ChatClientRequestSpec assembleSpec(
            ChatRequestContext ctx,
            ChatResources resources,
            List<Resource> skillDirs,
            @Nullable ToolCallingManager streamingToolManager,
            Sinks.@Nullable Many<ChatEvent> streamSink) {
        ChatRequest req = ctx.req();
        boolean streaming = streamingToolManager != null;

        ModelRouter.Provider provider = modelRouter.providerOf(req.modelName());
        ChatModel chatModel = modelRouter.resolve(req.modelName());
        DynamicMcpClientFactory.McpClients mcp = resources.mcp();
        Sandbox sandbox = resources.sandbox();

        ToolCallback[] skillCallbacks = skillDirs.isEmpty()
                ? new ToolCallback[0]
                : new ToolCallback[] { SkillsTool.builder().addSkillsResources(skillDirs).build() };
        List<ToolCallback> builtinCallbacks = filterBuiltinTools(req.tools());
        ChatOptions.Builder<?> optionsBuilder = buildOptionsBuilder(req, provider);

        // useServerMemory=true 走服务端 ChatMemory（按 sessionId 持久化），否则无状态、历史由请求体提供。
        boolean useServerMemory = Boolean.TRUE.equals(req.useServerMemory());
        List<Message> historyMessages = useServerMemory ? List.of() : toHistoryMessages(req.history());

        // 2.0.1 起用官方 ToolCallingAdvisor + 官方工具限额（DefaultToolCallingManager 内置
        // ToolCallLimits，跨轮累计），替换掉原自研 BoundedToolCallAdvisor。
        ToolCallingAdvisor.Builder advisorBuilder = ToolCallingAdvisor.builder();
        if (streaming) {
            // GA 已移除 streamToolCallResponses 选项；流式 tool_call 事件依赖最终聚合后的
            // ChatResponse 在 toEvents 里识别。
            advisorBuilder.toolCallingManager(withToolCallLimit(
                    streamingToolManager, req.maxToolIterations()));
        } else {
            advisorBuilder.toolCallingManager(withToolCallLimit(
                    ToolCallingManager.builder().build(), req.maxToolIterations()));
        }

        ChatClient.ChatClientRequestSpec spec = ChatClient.create(chatModel)
                .prompt()
                .user(req.query())
                .options(optionsBuilder)
                .advisors(advisorBuilder.build())
                .advisors(LOGGER_ADVISOR);

        if (useServerMemory) {
            spec = spec.advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, ctx.sessionId()));
        } else if (!historyMessages.isEmpty()) {
            spec = spec.messages(historyMessages);
        }

        // 提示词的请求级开关（如 TodoWrite 纪律段、用户人设段）由 SystemPromptComposer
        // 内部按 req 字段判定，调用方只管把 req 传进来。未来新增开关无需改这里。
        String systemPrompt = systemPromptComposer.compose(req, ctx.client());
        if (StringUtils.hasText(systemPrompt)) {
            spec = spec.system(systemPrompt);
        }

        // sandbox != null（skills 或显式 Claude 模式触发，见 needSandbox）时注入 Bash/Read/Write/Edit/
        // FinalAnswer，全部走沙箱，脚本无法触达宿主机。sandbox env 与 ToolContext 同源，让 skill 内
        // Python 脚本可通过 os.environ 读取 userId 等。FinalAnswer 随沙箱注入（不按 skills 收紧）。
        if (sandbox != null) {
            spec = spec.tools(
                    new SandboxBashTool(sandbox, sandboxProps.getExecTimeoutMs(),
                            buildSandboxEnv(ctx), isLocalSandbox()),
                    new SandboxFileSystemTools(sandbox),
                    new SandboxArtifactTool(sandbox, artifactStorage),
                    new SandboxGrepTool(sandbox),
                    new SandboxGlobTool(sandbox),
                    finalAnswerTool);
        }
        if (!builtinCallbacks.isEmpty()) {
            spec = spec.tools(builtinCallbacks);
        }
        if (skillCallbacks.length > 0) {
            spec = spec.tools((Object) skillCallbacks);
        }
        ToolCallback[] mcpCallbacks = mcp.getToolCallbacks();
        if (mcpCallbacks.length > 0) {
            spec = spec.tools((Object) mcpCallbacks);
        }
        ToolCallback[] externalCallbacks = buildExternalToolCallbacks(req);
        if (externalCallbacks.length > 0) {
            spec = spec.tools((Object) externalCallbacks);
        }

        // 子代理委派工具（TaskTool）：仅当请求带了 agents 时装配。子代理复用主 agent 的沙箱
        // 工具集（同 Sandbox 实例），frontmatter tools/disallowedTools 再做二次过滤。
        // 流式时透传 streamSink，子代理执行过程会作为 subagent_* 事件推到主流；非流式传 null。
        // 子代理不装配 TaskTool 本身（层级扁平，子代理不能再 spawn 子代理）。
        ToolCallback[] taskTools = buildTaskTool(ctx, sandbox, skillDirs, streamSink, mcpCallbacks, builtinCallbacks);
        if (taskTools != null) {
            // 配对工具 Task + TaskOutput 一次注册（共用同一 TaskRepository）。
            spec = spec.tools((Object) taskTools);
        }

        // 工具上下文：不会写进 LLM 的 JSON Schema，仅在工具方法的 ToolContext 参数里可见；
        // 始终注入（哪怕全为占位空串），避免 Spring AI 在工具需要 ToolContext 时因 map 为空报错。
        return spec.toolContext(buildToolContext(ctx));
    }

    /**
     * 给工具循环挂上官方限额（2.0.1 {@code DefaultToolCallingManager} 内置 {@code ToolCallLimits}，
     * 按当前 turn 跨轮累计工具调用次数）。{@code maxToolIterations} ≥1 时收紧为总次数上限
     * （{@code maxTotalToolCalls}），null/&le;0 保留官方默认兜底（per-tool 40 / total 150）。
     * 超限后 {@code ToolCallingAdvisor} 把违约作为单条 {@code finishReason=toolCallLimitExceeded}
     * 优雅收尾、停止循环，不进 reactor error channel，对 SSE 无破坏。
     * 限额由 manager 装饰器<strong>委托的</strong>官方 manager 承担，故此处用带限额的官方 manager
     * 重建装饰器（{@code ObservableToolCallingManager} / {@code SubagentToolCallingManager}）。
     */
    private static ToolCallingManager withToolCallLimit(ToolCallingManager manager, Integer maxToolIterations) {
        if (maxToolIterations == null || maxToolIterations <= 0 ) {
            return manager;   // 不收紧：保留官方默认兜底限额
        }

        var limited = ToolCallingManager.builder().maxTotalToolCalls(maxToolIterations);
        if (manager instanceof ObservableToolCallingManager obs) {
            return obs.withDelegate(limited.build());
        }
        if (manager instanceof SubagentToolCallingManager sub) {
            return sub.withDelegate(limited.build());
        }
        return limited.build();
    }

    /**
     * /chat/stream 的 per-request 上下文：把需要 close 的资源（mcp + sandbox lease）和不需要 close
     * 的输入（skill 列表）打包，交给 {@link Flux#using} 统一管理生命周期。
     */
    private record StreamContext(ChatResources resources, List<Resource> skillDirs) implements AutoCloseable {
        @Override
        public void close() {
            resources.close();
        }
    }

    /**
     * 把请求体里的 {@code history} 转成 Spring AI 的 {@link Message} 列表。
     * <ul>
     *   <li>role=1 → {@link UserMessage}</li>
     *   <li>role=2 → {@link AssistantMessage}</li>
     *   <li>其它取值（含 null、空 content）整条跳过，不抛异常。</li>
     * </ul>
     * 顺序按入参原样保留；调用方负责保证 user/assistant 交替。
     */
    private List<Message> toHistoryMessages(List<ChatRequest.HistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(history.size());
        for (ChatRequest.HistoryMessage h : history) {
            if (h == null || h.role() == null || !StringUtils.hasText(h.content())) {
                continue;
            }
            switch (h.role()) {
                case 1 -> messages.add(new UserMessage(h.content()));
                case 2 -> messages.add(new AssistantMessage(h.content()));
                default -> log.debug("Skip history entry with unsupported role={}", h.role());
            }
        }
        return messages;
    }

    /**
     * 把单个流式 {@link org.springframework.ai.chat.model.ChatResponse} 拆成 0..N 条结构化事件：
     * 深度思考增量 reasoning、文本增量 token、结束原因 + token 用量。
     * reasoning 分类见共享 {@link ReasoningExtractor}（各厂商暴露方式不同：DeepSeek 在独立
     * {@code reasoningContent} 字段，Anthropic 靠 {@code metadata.thinking} 标记）。
     */
    private Flux<ChatEvent> toEvents(org.springframework.ai.chat.model.ChatResponse resp) {
        if (resp == null) {
            return Flux.empty();
        }
        List<ChatEvent> events = new ArrayList<>();
        Generation gen = resp.getResult();
        if (gen != null) {
            AssistantMessage msg = gen.getOutput();
            if (msg != null) {
                String reasoning = ReasoningExtractor.reasoningOf(msg);
                if (reasoning != null && !reasoning.isEmpty()) {
                    events.add(ChatEvent.reasoning(reasoning));
                }
                // 正文增量只能判 null/empty，不能用 StringUtils.hasText：流式 token 经常出现纯空白
                // chunk（如代码块里的单独 \n），hasText 会过滤掉导致前端丢换行。reasoning 同理。
                String text = ReasoningExtractor.textOf(msg);
                if (text != null && !text.isEmpty()) {
                    events.add(ChatEvent.token(text));
                }

                // tool_call 事件不在这里发：GA 流式路径已被 advisor 硬过滤掉 hasToolCalls() 的 chunk，
                // 改由 ObservableToolCallingManager 在 executeToolCalls 入口旁路 emit。
            }
            ChatGenerationMetadata genMeta = gen.getMetadata();
            String finishReason = genMeta != null ? genMeta.getFinishReason() : null;
            if (StringUtils.hasText(finishReason)) {
                events.add(ChatEvent.finish(finishReason, usageOf(resp.getMetadata())));
            }
        }
        return Flux.fromIterable(events);
    }

    /**
     * 把非流式的整段 {@link org.springframework.ai.chat.model.ChatResponse} 拆成"正文 + 思考过程"两段，
     * 按 generation 顺序拼接以兼容多 Generation 情况（DeepSeek 一般 1 个；Anthropic 开 thinking 时
     * thinking 块单独成 Generation 且 metadata 含 {@code signature}）。
     */
    private ReasoningSplit extractReasoningAndText(org.springframework.ai.chat.model.@Nullable ChatResponse resp) {
        if (resp == null) {
            return new ReasoningSplit(null, null);
        }
        StringBuilder textBuf = new StringBuilder();
        StringBuilder reasoningBuf = new StringBuilder();
        for (Generation gen : resp.getResults()) {
            if (gen == null) {
                continue;
            }
            AssistantMessage msg = gen.getOutput();
            if (msg == null) {
                continue;
            }
            // 思考与正文分类见共享 ReasoningExtractor（与流式 toEvents 同源）。
            String reasoning = ReasoningExtractor.reasoningOf(msg);
            if (StringUtils.hasText(reasoning)) {
                reasoningBuf.append(reasoning);
            }
            String text = ReasoningExtractor.textOf(msg);
            if (StringUtils.hasText(text)) {
                textBuf.append(text);
            }
        }
        String text = textBuf.isEmpty() ? null : textBuf.toString();
        String reasoning = reasoningBuf.isEmpty() ? null : reasoningBuf.toString();
        return new ReasoningSplit(text, reasoning);
    }

    private record ReasoningSplit(@Nullable String text, @Nullable String reasoning) {}

    private Map<String, Object> usageOf(ChatResponseMetadata meta) {
        if (meta == null) {
            return null;
        }
        Usage usage = meta.getUsage();
        if (usage == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promptTokens", usage.getPromptTokens());
        m.put("completionTokens", usage.getCompletionTokens());
        m.put("totalTokens", usage.getTotalTokens());
        return m;
    }

    /**
     * 沿 cause 链找上游 HTTP 4xx/5xx 异常，把 status + 响应体打到日志里。
     *
     * <p>{@code WebClientResponseException.getMessage()} 只给出
     * {@code "400 Bad Request from POST https://..."}，<u>不</u>包含 body；而 DeepSeek/OpenAI
     * 的 400 错误恰恰是把"为什么拒"放在 body 的 {@code error.message} 里（如
     * "Messages with role 'tool' must be a response to ..."）。本方法专门补这一段，
     * 缺它时调试非 200 几乎只能猜。
     *
     * <ul>
     *   <li>{@link WebClientResponseException}：reactive 路径（Spring AI 的
     *       DeepSeek / OpenAI / Anthropic 等 starter 默认走 WebClient）。</li>
     *   <li>{@link HttpStatusCodeException}：阻塞式 RestClient/RestTemplate 路径
     *       （部分模型 starter 或自定义 tool 可能走这条）。</li>
     * </ul>
     *
     * <p>命中第一个匹配就返回——HTTP 异常通常只在 cause 链上出现一次，避免重复刷屏。
     * 防御性挡 {@code maxDepth} 是为了避免变态的循环 cause 链卡死日志线程。
     */
    private static void logUpstreamHttpBody(@Nullable Throwable e) {
        Throwable t = e;
        int maxDepth = 16;
        while (t != null && maxDepth-- > 0) {
            if (t instanceof WebClientResponseException w) {
                log.error("Upstream HTTP {} {} body: {}",
                        w.getStatusCode().value(), w.getStatusText(), w.getResponseBodyAsString());
                return;
            }
            if (t instanceof HttpStatusCodeException h) {
                log.error("Upstream HTTP {} body: {}",
                        h.getStatusCode().value(), h.getResponseBodyAsString());
                return;
            }
            t = t.getCause();
        }
    }

    /**
     * 构造每请求的工具上下文。这些字段以 {@code ToolContext} 形式传给工具方法，
     * 不会写入暴露给模型的 JSON Schema，适合放 userId、apiKey 这种敏感/与请求绑定的字段。
     *
     * <p>来源有两路，按以下顺序合并（后写覆盖先写）：
     * <ol>
     *   <li>固定字段：userId / assistantId / sessionId / apiKey（占位）。</li>
     *   <li>请求体 {@code req.toolContext()} 自由扩展。</li>
     *   <li>请求头 {@code X-Ctx-*}（剥前缀后）—— 网关/反向代理常通过 header 强制注入，
     *       因此放在最后，可覆盖请求体里的同名 key。</li>
     * </ol>
     */
    private Map<String, Object> buildToolContext(ChatRequestContext ctx) {
        ChatRequest req = ctx.req();
        Map<String, String> headerCtx = ctx.headerCtx();
        Map<String, Object> out = new LinkedHashMap<>();
        // Spring AI: MethodToolCallback.validateToolContextSupport requires a non-empty
        // map, and DefaultChatClient.toolContext(Map) rejects null values via
        // Assert.noNullElements. Use empty-string placeholders; tools detect "missing"
        // with isBlank().
        // userId / assistantId 单独透传一份（key 固定），方便工具按已知 key 直接读取。
        out.put(BuiltinTools.CTX_USER_ID, req.userId() != null ? String.valueOf(req.userId()) : "");
        out.put(BuiltinTools.CTX_ASSISTANT_ID, req.assistantId() != null ? String.valueOf(req.assistantId()) : "");
        out.put(BuiltinTools.CTX_SESSION_ID, req.sessionId() != null ? req.sessionId() : "");
        // 其它字段（apiKey、tenantId 等）由 req.toolContext() 自由扩展，无需再改这里。
        out.put(BuiltinTools.CTX_API_KEY, "");
        if (req.toolContext() != null) {
            for (Map.Entry<String, String> e : req.toolContext().entrySet()) {
                String k = e.getKey();
                if (k == null || k.isBlank()) {
                    continue;
                }
                out.put(k, e.getValue() != null ? e.getValue() : "");
            }
        }
        if (headerCtx != null) {
            for (Map.Entry<String, String> e : headerCtx.entrySet()) {
                String k = e.getKey();
                if (k == null || k.isBlank()) {
                    continue;
                }
                out.put(k, e.getValue() != null ? e.getValue() : "");
            }
        }
        return out;
    }

    /**
     * 沙箱子进程环境变量：与 ToolContext 同源，但走环境变量通道，
     * 让 skill 内的 Python/Shell 脚本通过 {@code os.environ} 读取。
     * key 自动转大写并把非 {@code [A-Z0-9_]} 字符替换为下划线，
     * value 为 null 的条目跳过（环境变量不接受 null）。
     *
     * <p>合并顺序同 {@link #buildToolContext}：header 路径在最后，可覆盖请求体里的同名 key。
     */
    private Map<String, String> buildSandboxEnv(ChatRequestContext ctx) {
        ChatRequest req = ctx.req();
        Map<String, String> headerCtx = ctx.headerCtx();
        Map<String, String> env = new LinkedHashMap<>();
        if (req.userId() != null) {
            env.put("USER_ID", String.valueOf(req.userId()));
        }
        if (req.assistantId() != null) {
            env.put("ASSISTANT_ID", String.valueOf(req.assistantId()));
        }
        if (req.sessionId() != null) {
            env.put("SESSION_ID", req.sessionId());
        }
        if (req.toolContext() != null) {
            for (Map.Entry<String, String> e : req.toolContext().entrySet()) {
                putEnv(env, e.getKey(), e.getValue());
            }
        }
        if (headerCtx != null) {
            for (Map.Entry<String, String> e : headerCtx.entrySet()) {
                putEnv(env, e.getKey(), e.getValue());
            }
        }
        return env;
    }

    /** key 规范化（大写 + 非 {@code [A-Z0-9_]} 替换为下划线）后写入 env；null/空 key、null value 直接跳过。 */
    private static void putEnv(Map<String, String> env, String key, String value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        env.put(key.toUpperCase().replaceAll("[^A-Z0-9_]", "_"), value);
    }

    /**
     * Local 沙箱模式下，{@code bash -lc} 会跑 login profile，且 Windows + WSL/Git Bash
     * 的边界经常把自定义 env 吃掉，所以需要让 {@link SandboxBashTool} 把 env 内联
     * {@code export} 进命令。Docker 模式则保持原样，由 {@code docker exec -e} 直注。
     */
    private boolean isLocalSandbox() {
        return sandboxProps.getMode() == SandboxProperties.Mode.LOCAL;
    }

    /**
     * 沙箱创建判定：skills 解析非空 <strong>或</strong> {@code includeClaudeBuiltinSubagents} 显式 true
     * 时建沙箱。<strong>用户 subagents 单独不触发</strong>——子代理以纯文本模式运行（无文件工具）。
     *
     * <p>解耦前是 {@code !skillDirs.isEmpty()}（沙箱与 skills 绑死），导致"没 skills 但开 Claude 模式"
     * 时主代理拿不到 Bash/Read/Write/Edit、模型幻觉调小写 {@code write} 撞 {@code No ToolCallback}。
     * 解耦后 Claude 模式（4 内置含文件系统工具）也建沙箱；用户 subagents 单独仍不建（纯文本子代理）。
     *
     * @param skillDirs 已解析的 skill 目录；空 list 表示无 skills 或解析全失败（后者边界忽略）
     */
    static boolean needSandbox(ChatRequest req, List<Resource> skillDirs) {
        if (skillDirs != null && !skillDirs.isEmpty()) {
            return true;
        }
        return Boolean.TRUE.equals(req.includeClaudeBuiltinSubagents());
    }

    /**
     * 把请求里声明的 {@code externalTools} 转成 {@link ToolCallback}：
     * 模型按 name/description/inputSchema 选择，命中后由
     * {@link ExternalToolPlatformRegistry} 按 {@code platform} 字段路由到对应
     * {@link ExternalToolPlatform} 执行（Dify / n8n / ...）。
     * {@code platform} 为空时回退默认平台，保证老请求继续可用。
     */
    private ToolCallback[] buildExternalToolCallbacks(ChatRequest req) {
        List<ChatRequest.ExternalTool> defs = req.externalTools();
        if (defs == null || defs.isEmpty()) {
            return new ToolCallback[0];
        }
        List<ToolCallback> list = new ArrayList<>(defs.size());
        for (ChatRequest.ExternalTool def : defs) {
            if (def == null || def.name() == null || def.name().isBlank()) {
                continue;
            }
            ExternalToolPlatform platform = externalToolPlatforms.resolve(def.platform());
            list.add(new ExternalToolCallback(def, platform));
        }
        return list.toArray(ToolCallback[]::new);
    }

    /**
     * fork 组装路径（{@code includeClaudeBuiltinSubagents=false}）用的 Task 工具描述模板。
     * 精简自库 {@code TaskTool.TASK_DESCRIPTION_TEMPLATE}（库那份 70 行且 private 拿不到），
     * 只保留"可用子代理列表 + 关键用法"——false 是用户主动精简的模式，描述越短越省 token。
     * {@code %s} 由 {@link #forkAssembleTask} 填入用户子代理的 registrations。
     */
    private static final String SUBAGENT_TASK_DESCRIPTION = """
            Launch a new agent to handle complex, multi-step tasks autonomously.

            Available agent types and the tools they have access to:
            %s

            When using the Task tool, you must specify a subagent_type parameter to select which agent type to use.

            Usage notes:
            - Always include a short description (3-5 words) summarizing what the agent will do.
            - Launch multiple agents concurrently whenever possible by sending a single message with multiple tool uses.
            - When the agent is done, it returns a single message back to you. The result is not visible to the user —
              send a text message to the user with a concise summary.
            - You can optionally run agents in the background (run_in_background). The Task tool returns a task_id;
              use the TaskOutput tool with it to retrieve results, continuing to work until you need them.
            - Provide clear, detailed prompts so the agent can work autonomously and return what you need.
            - If the user asks to run agents "in parallel", send a single message with multiple Task tool uses.
            """;

    /**
     * 构造 per-request 的配对工具 {@code Task}+{@code TaskOutput}：把请求体 {@code subagents}
     * （.md 文件 url 列表）解析成 {@link SubagentReference}，注册自定义 {@link SandboxSubagentExecutor}
     * （复用主 agent 沙箱工具 + SSE 旁路）+ 库 {@link ClaudeSubagentResolver}（解析 .md frontmatter）。
     *
     * <p>装配条件：{@code req.subagents()} 非空 <strong>或</strong>（{@code sandbox} 非空 <strong>且</strong>
     * {@code includeClaudeBuiltinSubagents} 非 false）。用户 subagents 单独（无沙箱）仍装配（纯文本
     * 子代理）；内置子代理必须有文件系统工具，仅在有沙箱时叠加。
     *
     * <p>内置开关（默认 true 仅沙箱存在时生效）：沙箱在且非 false → 走库 {@code TaskTool.Builder.build()}
     * （无条件追加 4 个 Claude 内置）；否则走 {@link #forkAssembleTask} fork 组装，只解析用户 subagents。
     *
     * <p>子代理与主 agent 共用同一 {@link Sandbox} 实例；MCP 工具与主 agent 同判定
     * （{@code if (mcpCallbacks.length > 0)}）一并下发到子代理工具集，builtinCallbacks 同理——
     * 两者均与沙箱无关，executor 的 frontmatter {@code tools}/{@code disallowedTools} 过滤天然覆盖。
     *
     * @return {@code Task}/{@code TaskOutput} 配对 {@link ToolCallback}（共用一个 per-request
     *         {@link DefaultTaskRepository}）；未装配返回 null
     */
    @Nullable ToolCallback[] buildTaskTool(ChatRequestContext ctx,
                                                 @Nullable Sandbox sandbox,
                                                 List<Resource> skillDirs,
                                                 Sinks.@Nullable Many<ChatEvent> streamSink,
                                                 @Nullable ToolCallback[] mcpCallbacks,
                                                 List<ToolCallback> builtinCallbacks) {
        ChatRequest req = ctx.req();
        boolean hasUser = req.subagents() != null && !req.subagents().isEmpty();
        // 内置子代理必须有文件系统工具 → 仅在 sandbox != null 时叠加（沙箱由 skills 或显式 Claude 模式触发）。
        // 用户 subagents 单独（无沙箱）仍装 Task，走 fork 路径（不叠加内置），子代理工具集为空。
        boolean builtinWithSandbox = sandbox != null && !Boolean.FALSE.equals(req.includeClaudeBuiltinSubagents());
        if (!hasUser && !builtinWithSandbox) {
            return null;   // 无用户子代理 且 无（沙箱+内置）→ 无 TaskTool 可装
        }
        // 1. 下载用户子代理 .md（hasUser 为 false 时跳过下载，agentFiles 为空 → 仅装 4 个内置）
        List<Resource> agentFiles = hasUser
                ? agentCache.resolve(req.userId(), req.assistantId(), ctx.sessionId(), req.subagents())
                : List.of();
        if (hasUser && agentFiles.isEmpty()) {
            log.warn("subagents requested but all downloads failed; skipping TaskTool assembly");
            return null;
        }
        // 2. 转成 SubagentReference（每个 .md → 一个 reference，kind=CLAUDE）。
        //    不用 ClaudeSubagentReferences.fromResources —— 它把 toAbsolutePath().toString() 塞进 uri，
        //    Windows 上形如 "X:/work/..."，ClaudeSubagentResolver.resolve 只对以 "/" 开头的路径补
        //    "file:" 前缀，盘符开头会被 DefaultResourceLoader 当 classpath 资源 → FileNotFoundException。
        //    这里直接用 Path.toUri() 生成标准 file URL，跨平台兼容。
        List<SubagentReference> userRefs = agentFiles.stream()
                .map(r -> {
                    try {
                        String uri = r.getFile().toPath().toAbsolutePath().toUri().toString();
                        return new SubagentReference(uri, ClaudeSubagentDefinition.KIND);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to resolve agent file path: " + r, e);
                    }
                })
                .toList();

        // 3. 子代理工具集：有沙箱才建沙箱工具（与主 agent 同源、同 Sandbox 实例，不注入 TaskTool
        //    自身——层级扁平）。SandboxBashTool 等是 @Tool 注解类，经 MethodToolCallbackProvider
        //    转成 ToolCallback[]；无沙箱时为空。
        ToolCallback[] subagentTools;
        if (sandbox != null) {
            Object[] subagentToolObjects = {
                    new SandboxBashTool(sandbox, sandboxProps.getExecTimeoutMs(),
                            buildSandboxEnv(ctx), isLocalSandbox()),
                    new SandboxFileSystemTools(sandbox),
                    new SandboxArtifactTool(sandbox, artifactStorage),
                    new SandboxGrepTool(sandbox),
                    new SandboxGlobTool(sandbox),
                    finalAnswerTool
            };
            subagentTools = MethodToolCallbackProvider.builder()
                    .toolObjects(subagentToolObjects)
                    .build()
                    .getToolCallbacks();
        } else {
            subagentTools = new ToolCallback[0];
        }

        // MCP / 内置工具均与沙箱无关（远程 API 或宿主无关），一并下发到子代理工具集；
        // executor 的 frontmatter tools/disallowedTools 过滤天然覆盖。无沙箱路径同样注入。
        List<ToolCallback> executorTools = new ArrayList<>(List.of(subagentTools));
        if (mcpCallbacks != null && mcpCallbacks.length > 0) {
            executorTools.addAll(List.of(mcpCallbacks));
        }
        if (!CollectionUtils.isEmpty(builtinCallbacks)) {
            executorTools.addAll(builtinCallbacks);
        }

        // 4. 构造 executor：流式时传 streamSink，非流式传 null。options supplier 把主 agent 的
        //    per-request 模型/thinking 配置下发到子代理（如 deepseek-reasoner 切换、Anthropic
        //    thinkingEnabled），否则子代理走 ChatModel 默认 options、thinking 不生效。
        //    每次 get() 返回全新 builder，避免跨子代理共享被 mutate 污染。
        ModelRouter.Provider provider = modelRouter.providerOf(req.modelName());
        SandboxSubagentExecutor executor = new SandboxSubagentExecutor(
                Map.of("default", modelRouter.chatClientBuilder(req.modelName())),
                List.copyOf(executorTools),
                skillDirs,
                streamSink,
                () -> buildOptionsBuilder(req, provider),
                toolPolicy,
                approvalRegistry,
                Boolean.TRUE.equals(req.bypassApproval()));

        // 5. 复用库的 ClaudeSubagentResolver 解析 .md，与本 executor 配对成 SubagentType
        SubagentType claudeType = new SubagentType(new ClaudeSubagentResolver(), executor);

        // 6. 构造配对工具 Task + TaskOutput：两者必须共用同一个 per-request TaskRepository，
        //    否则 Task 把 task_id 写进 repo A、TaskOutput 去 repo B 查 → "No background task found"。
        //    builtinWithSandbox=true 走库 build()（追加 4 内置）；false 走 forkAssembleTask（仅用户 refs）。
        DefaultTaskRepository repo = new DefaultTaskRepository();
        ToolCallback taskTool = builtinWithSandbox
                ? TaskTool.builder()
                        .subagentReferences(userRefs)   // 可空 → 库仅产 4 内置
                        .subagentTypes(claudeType)
                        .taskRepository(repo)
                        .build()
                : forkAssembleTask(userRefs, executor, repo);
        ToolCallback taskOutputTool = TaskOutputTool.builder()
                .taskRepository(repo)
                .build();
        return new ToolCallback[]{ taskTool, taskOutputTool };
    }

    /**
     * 方案 A 的 fork 组装：不调 {@code TaskTool.Builder.build()}（它会在内部无条件追加 4 个 Claude
     * 内置子代理），而是自己复刻 build() 的 4 步——解析用户 refs → 拼 registrations →
     * {@link TaskTool.TaskFunction} → {@link FunctionToolCallback}，让 {@code Task} 工具描述里
     * 只出现用户声明的子代理。模型硬调未声明的 subagent_type 会撞 "No subagent found"。
     */
    private ToolCallback forkAssembleTask(List<SubagentReference> userRefs,
                                          SandboxSubagentExecutor executor,
                                          TaskRepository repo) {
        ClaudeSubagentResolver resolver = new ClaudeSubagentResolver();
        List<SubagentDefinition> defs = userRefs.stream()
                .map(resolver::resolve)
                .toList();
        String registrations = String.join("\n",
                defs.stream().map(SubagentDefinition::toSubagentRegistrations).toList());
        TaskTool.TaskFunction fn = new TaskTool.TaskFunction(defs, List.of(executor), repo);
        return FunctionToolCallback.builder("Task", fn)
                .description(SUBAGENT_TASK_DESCRIPTION.formatted(registrations))
                .inputType(TaskCall.class)
                .build();
    }

    /**
     * 把所有"内置工具来源"统一喂给 {@code MethodToolCallbackProvider}，按 {@code req.tools}
     * 工具名白名单过滤。来源包括：
     * <ul>
     *   <li>{@link BuiltinTools}：永远在 —— {@code getDateTime} / {@code getCpuCount} 等</li>
     *   <li>{@link ProxiedBraveWebSearchTool}：可选，工具名 {@code WebSearch}</li>
     *   <li>{@link SmartWebFetchTool}：可选，工具名 {@code WebFetch}</li>
     *   <li>{@link TodoWriteTool}：永远在，工具名 {@code TodoWrite} —— 任务编排</li>
     * </ul>
     * 四类来源对模型而言无差别，都是 {@code @Tool} 方法。
     */
    private List<ToolCallback> filterBuiltinTools(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<Object> toolObjects = new ArrayList<>();
        toolObjects.add(builtinTools);
        toolObjects.add(todoWriteTool);
        if (braveWebSearchTool != null) {
            toolObjects.add(braveWebSearchTool);
        }
        if (smartWebFetchTool != null) {
            toolObjects.add(smartWebFetchTool);
        }
        ToolCallback[] all = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build()
                .getToolCallbacks();
        List<ToolCallback> filtered = new ArrayList<>();
        for (ToolCallback cb : all) {
            if (requested.contains(cb.getToolDefinition().name())) {
                filtered.add(cb);
            }
        }
        return filtered;
    }

    /**
     * 按 provider 构造 {@link ChatOptions.Builder}。{@code modelName} 一律透传给上游。
     * {@code thinking} 统一为三态，映射到各家的原生开关/档位：
     * <ul>
     *   <li>{@code "enabled"} → 开思考；{@code "disabled"} → 关思考；{@code null}/其他 → 不上覆写，走 provider 默认。</li>
     *   <li>Anthropic：{@code thinkingEnabled(budget)} / {@code thinkingDisabled()}（开时须抬 maxTokens，预算 < maxTokens）。</li>
     *   <li>DeepSeek：{@code thinking(ENABLED|DISABLED)} + {@code reasoningEffort(HIGH)}；DeepSeek 的思考输出走
     *       独立的 {@code reasoningContent} 字段，由 {@code ReasoningExtractor} 统一抽取。</li>
     *   <li>OpenAI：无纯开关，用 {@code reasoningEffort} 表达 —— enabled→{@code "high"}，disabled→{@code "minimal"}。</li>
     *   <li>MY_MODEL：{@code thinking} 字符串原样透传给自定义模型服务。</li>
     * </ul>
     */
    private ChatOptions.Builder<?> buildOptionsBuilder(ChatRequest req, ModelRouter.Provider provider) {
        Double temperature = req.temperature();
        String modelName = req.modelName();
        String thinking = req.thinking();
        boolean thinkOn = "enabled".equalsIgnoreCase(thinking);
        boolean thinkOff = "disabled".equalsIgnoreCase(thinking);

        return switch (provider) {
            case ANTHROPIC -> {
                AnthropicChatOptions.Builder b = AnthropicChatOptions.builder();
                if (StringUtils.hasText(modelName)) {
                    b.model(modelName);
                }
                if (temperature != null) {
                    b.temperature(temperature);
                }
                if (thinkOn) {
                    // 开思考须抬 maxTokens：Anthropic 约束 budget 必须 >= 1024 且 < maxTokens，否则 400。
                    b.maxTokens(16384).thinkingEnabled(10000L);
                } else if (thinkOff) {
                    b.thinkingDisabled();
                }
                yield b;
            }
            case DEEPSEEK -> {
                DeepSeekChatOptions.Builder b = DeepSeekChatOptions.builder();
                if (StringUtils.hasText(modelName)) {
                    b.model(modelName);
                }
                if (temperature != null) {
                    b.temperature(temperature);
                }
                if (thinkOn) {
                    b.thinking(Thinking.ENABLED).reasoningEffort(ReasoningEffort.HIGH);
                } else if (thinkOff) {
                    b.thinking(Thinking.DISABLED);
                }
                yield b;
            }
            case OPENAI -> {
                OpenAiChatOptions.Builder b = OpenAiChatOptions.builder();
                if (StringUtils.hasText(modelName)) {
                    b.model(modelName);
                }
                if (temperature != null) {
                    b.temperature(temperature);
                }
                if (thinkOn) {
                    b.reasoningEffort("high");
                } else if (thinkOff) {
                    b.reasoningEffort("minimal");
                }
                yield b;
            }
            case MY_MODEL -> {
                com.example.chat.mymodel.MyModelChatOptions.Builder b =
                        com.example.chat.mymodel.MyModelChatOptions.builder();
                if (StringUtils.hasText(modelName)) {
                    b.model(modelName);
                }
                if (temperature != null) {
                    b.temperature(temperature);
                }
                if (StringUtils.hasText(thinking)) {
                    b.thinking(thinking);
                }
                // 透传 /chat/stream 入参中的 userId / assistantId 到自定义模型服务（统计维度埋点用）。
                if (req.userId() != null) {
                    b.userId(req.userId());
                }
                if (req.assistantId() != null) {
                    b.assistantId(req.assistantId());
                }
                yield b;
            }
        };
    }
}
