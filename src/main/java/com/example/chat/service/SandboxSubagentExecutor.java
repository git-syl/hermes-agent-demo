package com.example.chat.service;

import com.example.chat.api.dto.ChatEvent;
import com.example.chat.config.ToolPolicyProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.common.task.subagent.SubagentDefinition;
import org.springaicommunity.agent.common.task.subagent.SubagentExecutor;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentDefinition;
import org.springaicommunity.agent.tools.SkillsTool.Skill;
import org.springaicommunity.agent.utils.Skills;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 自定义 {@link SubagentExecutor}，把 Claude 风格子代理的执行接入本项目的沙箱工具链与 SSE 流。
 *
 * <p>不直接用库 {@code ClaudeSubagentExecutor} 的三个原因：
 * <ol>
 *   <li>库的 executor 工具集由 {@code ClaudeSubagentType.Builder} 硬编码（Grep/Glob/Shell/
 *       FileSystem/...），全是宿主机工具 —— docker 沙箱模式下子代理能读写宿主机，<strong>安全风险
 *       不可接受</strong>。本类让子代理复用主 agent 的沙箱工具，与主 agent 同一沙箱会话。</li>
 *   <li>库的 {@code execute()} 是同步 {@code .call().content()}，无流式无事件钩子 —— 前端看不到
 *       子代理执行过程。本类在 sink 非 null 时走流式，把 {@code subagent_token} 等推到主流 sink。</li>
 *   <li>库的 ToolCallAdvisor 用默认 {@code ToolCallingManager}，无法观测工具执行。本类注入
 *       {@link SubagentToolCallingManager} 做旁路观测。</li>
 * </ol>
 *
 * <p><b>与 {@code ClaudeSubagentResolver} 配对</b>：本 executor {@link #getKind()} 返回
 * {@code ClaudeSubagentDefinition.KIND}，与库 resolver 同 kind，两者在 {@code SubagentType} 里成对注册。
 *
 * <h2>流式 vs 同步</h2>
 * <ul>
 *   <li>sink != null（流式 {@code /chat/stream}）：{@code .stream().chatResponse()} 拉流，
 *       每个 chunk emit {@code subagent_token}；完成后 emit {@code subagent_finish} 并返回拼接全文。</li>
 *   <li>sink == null（非流式 {@code /chat}）：{@code .call().content()} 同步返回，无事件 emit。</li>
 * </ul>
 *
 * <h2>工具过滤</h2>
 * <p>完全复刻 {@code ClaudeSubagentExecutor.createTaskChatClient} 的过滤逻辑：
 * frontmatter {@code tools} 白名单 / {@code disallowedTools} 黑名单；两者都未声明则继承全部
 * 下发工具（沙箱工具 + MCP + 主 agent 内置工具）。
 *
 * <h2>子代理不嵌套 HITL / 不嵌套子代理</h2>
 * <p>子代理内部工具调用走 {@link SubagentToolCallingManager}（只观测、不阻塞）—— 子代理是"隔离的
 * 自治执行单元"，不应再向用户弹审批；需要审批的敏感操作应在主 agent 工具链上完成。也不把
 * {@code TaskTool} 注入子代理工具集（Spring 官方要求层级扁平，子代理不能再 spawn 子代理）。
 */
public class SandboxSubagentExecutor implements SubagentExecutor {

    private static final Logger log = LoggerFactory.getLogger(SandboxSubagentExecutor.class);

    private final Map<String, ChatClient.Builder> chatClientBuilderMap;
    private final List<ToolCallback> tools;
    private final List<String> skillsDirectories;
    /** 流式时非 null（emit subagent_* 事件）；非流式 null（静默同步执行）。 */
    private final Sinks.@Nullable Many<ChatEvent> sink;
    /**
     * 每次装配子代理 ChatClient 时调用，返回一个全新的 {@link ChatOptions.Builder}，用于把
     * 主 agent 的 per-request 模型/thinking 配置（如 deepseek-reasoner 切换、reasoning_effort）
     * 下发到子代理。null 时不设 defaultOptions（子代理走 ChatModel 自身默认 options）。
     * <p>用 {@code Supplier} 而非单例 builder：同一请求内主 agent 可能先后调多个子代理，
     * 各自拿独立 builder 实例，彻底规避 builder 被某次调用 mutate 后污染下一个子代理的风险。
     */
    private final @Nullable Supplier<ChatOptions.Builder<?>> defaultOptionsSupplier;
    /** HITL 审批策略（绑 chat.hitl.*），下发给子代理的 SubagentToolCallingManager。 */
    private final ToolPolicyProperties toolPolicy;
    /** 审批回填注册表，与主 agent 共用同一单例（requestId 全局唯一）。 */
    private final ApprovalRegistry approvalRegistry;
    /** per-request 绕行开关，来源 ChatRequest.bypassApproval，对子代理同样生效。 */
    private final boolean bypassApproval;

    /**
     * 测试 / 非 HITL 便利构造：空白名单策略 + 独立 registry + 不绕行。生产装配走全参构造。
     */
    public SandboxSubagentExecutor(Map<String, ChatClient.Builder> chatClientBuilderMap,
                                   List<ToolCallback> tools,
                                   @Nullable List<Resource> skillResources,
                                   Sinks.@Nullable Many<ChatEvent> sink) {
        this(chatClientBuilderMap, tools, skillResources, sink, null,
                new ToolPolicyProperties(), new ApprovalRegistry(), false);
    }

    /**
     * 全参构造（生产入口，由 {@code ChatService.buildTaskTool} 调用）。
     *
     * @param defaultOptionsSupplier 每次装配子代理 ChatClient 时调用，返回 per-request 的
     *                               {@link ChatOptions.Builder}（跟随主 agent 的 thinking 配置），null 则不覆盖
     * @param toolPolicy             HITL 策略，下发给子代理工具循环 gate
     * @param approvalRegistry       审批回填注册表（与主 agent 共用）
     * @param bypassApproval         per-request 绕行开关（跟随主请求）
     */
    public SandboxSubagentExecutor(Map<String, ChatClient.Builder> chatClientBuilderMap,
                                   List<ToolCallback> tools,
                                   @Nullable List<Resource> skillResources,
                                   Sinks.@Nullable Many<ChatEvent> sink,
                                   @Nullable Supplier<ChatOptions.Builder<?>> defaultOptionsSupplier,
                                   ToolPolicyProperties toolPolicy,
                                   ApprovalRegistry approvalRegistry,
                                   boolean bypassApproval) {
        Assert.notEmpty(chatClientBuilderMap, "chatClientBuilderMap must not be empty");
        Assert.isTrue(chatClientBuilderMap.containsKey("default"),
                "chatClientBuilderMap must contain a default ChatClient.Builder with key 'default'");
        Assert.notNull(tools, "tools must not be null");
        Assert.notNull(toolPolicy, "toolPolicy must not be null");
        Assert.notNull(approvalRegistry, "approvalRegistry must not be null");
        this.chatClientBuilderMap = chatClientBuilderMap;
        this.tools = tools;
        this.skillsDirectories = toDirectoryPaths(skillResources);
        this.sink = sink;
        this.defaultOptionsSupplier = defaultOptionsSupplier;
        this.toolPolicy = toolPolicy;
        this.approvalRegistry = approvalRegistry;
        this.bypassApproval = bypassApproval;
    }

    @Override
    public String getKind() {
        return ClaudeSubagentDefinition.KIND;
    }

    @Override
    public String execute(TaskCall taskCall, SubagentDefinition subagent) {
        ClaudeSubagentDefinition claudeSubagent = (ClaudeSubagentDefinition) subagent;
        String name = claudeSubagent.getName();
        log.info("subagent.start name='{}' prompt.length={}", name, taskCall.prompt().length());

        ChatClient taskChatClient = createTaskChatClient(claudeSubagent);
        String systemPrompt = claudeSubagent.getContent()
                + buildPreloadedSkillsSuffix(claudeSubagent)
                + buildNoToolsSuffix();

        if (sink != null) {
            return executeStreaming(taskChatClient, name, systemPrompt, taskCall.prompt());
        }
        return executeSync(taskChatClient, systemPrompt, taskCall.prompt());
    }

    // ---------------------------------------------------------------------------------------------
    // 执行路径
    // ---------------------------------------------------------------------------------------------

    private String executeSync(ChatClient chatClient, String systemPrompt, String userPrompt) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    /**
     * 流式执行：每个 chunk emit {@code subagent_token}，工具循环期内由
     * {@link SubagentToolCallingManager} 推 {@code subagent_tool_call}/{@code subagent_tool_result}。
     *
     * <p>用 {@code blockLast()} 等流结束 —— {@code executeToolCalls} 在 ToolCallAdvisor 内部
     * 已调度到 boundedElastic，这里阻塞当前线程是安全的（子代理本身就在独立调用栈里跑）。
     */
    private String executeStreaming(ChatClient chatClient, String name, String systemPrompt, String userPrompt) {
        emit(ChatEvent.subagentStart(name, userPrompt));
        StringBuilder buf = new StringBuilder();
        @Nullable String[] finishReason = new @Nullable String[]{null};
        try {
            chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        Generation gen = resp.getResult();
                        if (gen != null && gen.getOutput() != null) {
                            AssistantMessage msg = gen.getOutput();
                            // 思考增量：DeepSeek reasoning_content / Anthropic thinking content。
                            // 与主 agent ChatService.toEvents 同款分类（共享 ReasoningExtractor），
                            // emit 成 subagent_reasoning，不计入 buf——思考过程不回流入子代理最终输出。
                            String reasoning = ReasoningExtractor.reasoningOf(msg);
                            if (reasoning != null && !reasoning.isEmpty()) {
                                emit(ChatEvent.subagentReasoning(name, reasoning));
                            }
                            // 正文增量：非思考块的 content。
                            String text = ReasoningExtractor.textOf(msg);
                            if (text != null && !text.isEmpty()) {
                                buf.append(text);
                                emit(ChatEvent.subagentToken(name, text));
                            }
                        }
                        String reason = extractFinishReason(resp);
                        if (reason != null) {
                            finishReason[0] = reason;
                        }
                    })
                    .blockLast();
        } catch (RuntimeException e) {
            log.warn("subagent.stream.failed name='{}' error={}", name, e.getMessage());
            throw e;
        }
        emit(ChatEvent.subagentFinish(name, finishReason[0] != null ? finishReason[0] : "STOP"));
        log.info("subagent.finish name='{}' output.length={}", name, buf.length());
        return buf.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // ChatClient 装配
    // ---------------------------------------------------------------------------------------------

    /**
     * 复刻 {@code ClaudeSubagentExecutor.createTaskChatClient} 的工具过滤 + advisor 装配，
     * 关键差异：流式时注入 {@link SubagentToolCallingManager} 做 SSE 旁路。
     */
    private ChatClient createTaskChatClient(ClaudeSubagentDefinition claudeSubagent) {
        ChatClient.Builder builder = doFindChatClientBuilder(claudeSubagent).clone();

        // 把主 agent 的 per-request options（模型路由 / thinking 配置，如 deepseek-reasoner
        // 切换、reasoning_effort、Anthropic thinkingEnabled）下发到子代理，保证子代理跟随主 agent
        // 的深度思考开关。每次取全新 builder，避免跨子代理共享被 mutate 污染。
        if (this.defaultOptionsSupplier != null) {
            builder.defaultOptions(this.defaultOptionsSupplier.get());
        }

        if (!CollectionUtils.isEmpty(this.tools)) {
            List<ToolCallback> subagentTools = new ArrayList<>(this.tools);
            // allowed tools filtering
            if (!CollectionUtils.isEmpty(claudeSubagent.tools())) {
                subagentTools = this.tools.stream()
                        .filter(tc -> claudeSubagent.tools().contains(tc.getToolDefinition().name()))
                        .toList();
            }
            // disallowed tools filtering
            if (!CollectionUtils.isEmpty(claudeSubagent.disallowedTools())) {
                subagentTools = subagentTools.stream()
                        .filter(tc -> !claudeSubagent.disallowedTools().contains(tc.getToolDefinition().name()))
                        .toList();
            }
            // Spring AI 2.0 GA：defaultToolCallbacks(...) 全部 @Deprecated(forRemoval)，
            // 统一走 defaultTools(Object...)。与 ChatService.assembleSpec 的 spec.tools((Object) arr) 同模式：
            // 数组 + (Object) cast 直接命中 tools(...) 内 instanceof ToolCallback[] 分支，零额外分配。
            builder.defaultTools((Object) subagentTools.toArray(ToolCallback[]::new));
        }

        if (!"default".equals(claudeSubagent.permissionMode())) {
            log.warn("subagent.permissionMode not supported yet (name='{}', mode={})",
                    claudeSubagent.getName(), claudeSubagent.permissionMode());
        }

        // 流式时注入 SubagentToolCallingManager 做 SSE 旁路 + HITL gate（命中白名单的工具等前端审批）；
        // 非流式用默认 ToolCallingManager（与主 agent /chat 同：非流式不走 HITL）。
        // Spring AI 2.0.1 起用官方 ToolCallingAdvisor + 官方工具限额（DefaultToolCallingManager 内置，
        // 默认 per-tool 40 / total 150，跨轮累计），替换掉原自研 BoundedToolCallAdvisor。
        ToolCallingAdvisor.Builder advisorBuilder = ToolCallingAdvisor.builder();
        if (sink != null) {
            ToolCallingManager observableManager = new SubagentToolCallingManager(
                    ToolCallingManager.builder().build(), sink, claudeSubagent.getName(),
                    toolPolicy, approvalRegistry, bypassApproval);
            advisorBuilder.toolCallingManager(observableManager);
        }
        builder.defaultAdvisors(advisorBuilder.build());
        return builder.build();
    }

    /**
     * 复刻 {@code ClaudeSubagentExecutor.doFindChatClientBuilder} 的 provider:model 路由逻辑。
     * 由于原方法是 {@code protected}，本类无法继承调用 —— 这里复制实现，行为与库完全一致。
     */
    private ChatClient.Builder doFindChatClientBuilder(ClaudeSubagentDefinition claudeSubagent) {
        if (StringUtils.hasText(claudeSubagent.getModel())) {
            String providerName = "default";
            String modelRef = claudeSubagent.getModel();
            String modelName = modelRef.trim();

            if (modelRef.contains(":")) {
                String[] parts = modelRef.split(":", 2);
                if (StringUtils.hasText(parts[0])) {
                    providerName = parts[0].trim();
                }
                if (parts.length > 1 && StringUtils.hasText(parts[1])) {
                    modelName = parts[1].trim();
                }
            }

            if (this.chatClientBuilderMap.containsKey(providerName)) {
                return this.chatClientBuilderMap.get(providerName);
            }
        }
        return this.chatClientBuilderMap.get("default");
    }

    // ---------------------------------------------------------------------------------------------
    // 预加载 skills（复刻 ClaudeSubagentExecutor 的 preloadedSkillsSystemSuffix）
    // ---------------------------------------------------------------------------------------------

    /**
     * 把 frontmatter {@code skills: [ai-tutor, ...]} 命中的 skill 内容拼到 system prompt 末尾。
     * 与 {@code ClaudeSubagentExecutor} 完全一致的语义：full skill content injected, not just
     * made available for invocation.
     *
     * <p>加载失败时降级为空串 —— 子代理仍可正常执行，只是没有 skill 知识补充。与库里行为一致。
     */
    private String buildPreloadedSkillsSuffix(ClaudeSubagentDefinition claudeSubagent) {
        if (CollectionUtils.isEmpty(claudeSubagent.skills()) || CollectionUtils.isEmpty(this.skillsDirectories)) {
            return "";
        }
        try {
            List<Skill> skills = Skills.loadDirectories(this.skillsDirectories);
            return "\n" + skills.stream()
                    .filter(s -> claudeSubagent.skills().contains(s.name()))
                    .map(skill -> "%s\nBase directory for this skill: %s\n\n%s".formatted(
                            skill.toXml(), skill.basePath(), skill.content()))
                    .collect(Collectors.joining("\n\n"));
        } catch (RuntimeException e) {
            log.warn("subagent.skills.load.failed name='{}' skills={} error={}",
                    claudeSubagent.getName(), claudeSubagent.skills(), e.getMessage());
            return "";
        }
    }

    /**
     * 子代理工具集为空（无沙箱，用户 subagents 单独运行）时追加一句提示，避免子代理幻觉调用未注册的
     * 文件系统 / Bash 工具。沙箱在时 {@link #tools} 非空，不追加。
     */
    private String buildNoToolsSuffix() {
        if (!this.tools.isEmpty()) {
            return "";
        }
        return "\n\n注：本次未提供文件系统 / Bash 等工具（未启用沙箱），请直接以文本作答，不要尝试调用任何工具。";
    }

    // ---------------------------------------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------------------------------------

    /** 从 Resource 列表抽取绝对目录路径，供 {@link Skills#loadDirectories} 使用。 */
    private static List<String> toDirectoryPaths(@Nullable List<Resource> skillResources) {
        if (CollectionUtils.isEmpty(skillResources)) {
            return List.of();
        }
        List<String> dirs = new ArrayList<>(skillResources.size());
        for (Resource r : skillResources) {
            try {
                dirs.add(Paths.get(r.getURL().toURI()).toAbsolutePath().toString());
            } catch (Exception e) {
                // 单个 skill 目录解析失败：跳过，不阻塞子代理装配。
                log.debug("skip skill resource (resolve failed): {}", e.getMessage());
            }
        }
        return dirs;
    }

    /**
     * 从单个流式 {@link ChatResponse} 抽取 finish reason。
     */
    private static @Nullable String extractFinishReason(ChatResponse resp) {
        if (resp == null) return null;
        Generation gen = resp.getResult();
        if (gen == null) return null;
        var meta = gen.getMetadata();
        return meta != null ? meta.getFinishReason() : null;
    }

    private void emit(ChatEvent event) {
        if (sink == null) return;
        Sinks.EmitResult er = sink.tryEmitNext(event);
        if (er.isFailure()) {
            log.debug("emit {} failed (subagent={})", event.type(), event.name());
        }
    }
}
