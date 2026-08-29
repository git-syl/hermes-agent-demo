package com.example.chat.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Inbound payload for POST /chat. All fields are optional except {@code query}; sensible
 * defaults are applied downstream.
 */
public record ChatRequest(
        List<String> tools,
        Map<String, McpServerConfig> mcpConfig,
        List<SkillRef> skills,
        /**
         * Per-request 子代理（Claude 风格 .md 文件）列表。每项是 {name, url}，url 指向单个 .md 文件，
         * 由 {@code AgentCacheService} 下载缓存，主 agent 通过 {@code Task} 工具委派。
         * 与 {@link #skills()}（目录型 zip 资源）缓存/加载路径不同；留空则不装配 {@code TaskTool}
         * （除非 {@link #includeClaudeBuiltinSubagents()} 为 true）。
         */
        List<SubagentRef> subagents,
        String sessionId,
        Double temperature,
        String thinking,
        Long userId,
        /**
         * 助手（智能体）ID。两用途：① skill 缓存目录分桶（{@code userId/assistantId/sessionId/...}）；
         * ② 统计透传，随请求下沉到自定义模型服务，作为调用方维度埋点。
         */
        Long assistantId,
        String modelName,
        String query,
        /** 用户自定义助手人设；与内置系统提示词拼接，内置在前、用户在后。 */
        String system,
        /**
         * 每请求自定义上下文，key/value 注入到工具调用：内置工具以 {@code ToolContext} 可见；
         * skill 脚本以环境变量可见（key 转大写、非 {@code [A-Z0-9_]} 替换为下划线）。适合放 apiKey 等。
         */
        Map<String, String> toolContext,
        /**
         * 外部 HTTP 工具：由本服务统一转发到 {@code chat.external-tools.endpoint} 配置的端点执行。
         * 每项含工具名/描述/JSON Schema（与 OpenAI function 定义同形）；端点只接受服务端配置，防 SSRF。
         */
        List<ExternalTool> externalTools,
        /**
         * 多轮对话历史。服务端不依赖内置 ChatMemory，调用方每次需带完整历史。
         * role：{@code 1=user}、{@code 2=assistant}；system 由 {@link #system()} 字段单独传。
         * 仅当 {@link #useServerMemory()} 非 true 时生效。
         */
        List<HistoryMessage> history,
        /**
         * 是否启用服务端 ChatMemory（按 {@link #sessionId()} 持久化）：
         * {@code true} 时服务端经 {@code MessageChatMemoryAdvisor} 自管历史，{@link #history()} 被忽略，
         * 同一 sessionId 多次请求自动续接；{@code false}/{@code null}（默认）为无状态，历史必须由调用方带上。
         * 两种模式互斥。
         */
        Boolean useServerMemory,
        /**
         * 工具调用循环的"总调用次数"上限（防死循环），映射到 Spring AI 2.0.1
         * {@code DefaultToolCallingManager.maxTotalToolCalls}。计数对象是工具被调用次数（非模型轮数）。
         * <ul>
         *   <li>{@code null}/{@code <=0}（默认）：走官方默认兜底（单工具 40 / 全工具 150）。</li>
         *   <li>{@code >=1}：本 turn 内工具调用总次数的实际熔断阈值。</li>
         * </ul>
         * 超限不抛错：{@code ToolCallingAdvisor} 捕获后下发 {@code finishReason=toolCallLimitExceeded}
         * 正常 chunk 并停循环，同步与流式都不进 error channel。推荐：单步工具 3~5 / 多步 agent 15~25 /
         * 长链路探索 30~50。该字段不进模型 JSON Schema。
         */
        Integer maxToolIterations,
        /**
         * Human-in-the-Loop 审批的 per-request 绕行开关。
         * {@code null}/{@code false}（默认）：按 {@code chat.hitl.required-tools} 白名单正常 gate，
         * 命中工具走 {@code approval_request} → {@code POST /chat/approval} 双通道审批；
         * {@code true}：本次请求内所有需审批工具直接放行 —— 不发审批事件、不注册 future、不等回填
         * （是"跳过审批流程"而非"自动点同意"，前端不会收到任何审批相关事件）。
         * 用途：B2B 集成（上游已做授权/审计）、批处理/自动化、已在调用方 UI 弹过审批的场景。
         * <b>安全</b>：开启即完全信任调用方，生产若对终端用户开放禁止透传给前端控制；
         * 每个被绕行工具都会记 {@code hitl.bypassed} 审计日志。
         */
        Boolean bypassApproval,
        /**
         * 是否叠加库自带的 4 个 Claude 内置子代理（{@code general-purpose/Explore/Plan/Bash}，
         * 来自 spring-ai-agent-utils 的 {@code classpath:/agent/*_SUBAGENT.md}）。
         * <ul>
         *   <li>{@code null}/{@code true}（默认）：仅当沙箱已存在时叠加（内置含文件工具，必须沙箱）；</li>
         *   <li>{@code false}：不叠加，仅装配 {@link #subagents()} 用户声明的子代理。</li>
         * </ul>
         * 沙箱创建（{@code ChatService.needSandbox}）：仅 {@link #skills()} 非空 <strong>或</strong>
         * 本字段显式 true 时建沙箱；本字段 null 不触发建沙箱；用户 subagents 单独不建沙箱（纯文本模式）。
         */
        Boolean includeClaudeBuiltinSubagents
) {

    public record SkillRef(String name, String url) {}

    /**
     * 子代理引用：{@code url} 指向单个 Claude 风格 .md（frontmatter 里 {@code name} 才是模型可见名，
     * 与本字段 {@code name} 不必一致）；本字段 {@code name} 仅用于缓存路径分桶与日志。
     */
    public record SubagentRef(String name, String url) {}

    public record McpServerConfig(
            String url,
            Map<String, String> headers
    ) {}

    /** 历史消息条目；{@code role}：{@code 1}→{@code UserMessage}、{@code 2}→{@code AssistantMessage}。 */
    public record HistoryMessage(String content, Integer role) {}

    /**
     * 外部工具定义。{@code inputSchema} 为 JSON Schema 字符串（与 OpenAI function 定义同形）；
     * {@code platform} 对应服务端注册的 {@code ExternalToolPlatform#name()}（如 dify、n8n），
     * 留空回退到默认平台（dify）；{@code config} 是平台私有的覆盖配置（如 Dify 的 appToken）。
     */
    public record ExternalTool(
            String name,
            String description,
            String inputSchema,
            String platform,
            Map<String, Object> config
    ) {}
}
