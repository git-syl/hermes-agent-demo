package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

/**
 * Human-in-the-Loop（HITL）审批策略。绑定 {@code chat.hitl.*}。
 *
 * <p><b>设计原则</b>：单一事实源 + 工具名白名单。
 * 一个工具是否需要"调用前问人"由 {@link #requiredTools} 决定，不在第二处地方配置。
 * 没有命中白名单的工具走原有快路径，零额外开销。
 *
 * <p><b>fail-safe</b>：{@link #timeout} 到期后，前端仍未回填决定的审批一律按
 * {@code DECLINE} 处理（在 {@code ApprovalRegistry} 的 {@code orTimeout} 兜底，
 * 不依赖前端发送 cancel 消息）。这意味着前端断连 / 用户离开界面 / 后台 crash
 * 都不会让 REQUIRED 工具静默执行。
 *
 * <p><b>命中规则</b>：工具名严格相等比较，对应工具方法上 {@code @Tool(name=...)}
 * 注解的值；大小写敏感。例如 {@code SandboxBash}、{@code WriteFile}。
 *
 * <p>未来扩展：如果需要 risk-level / 提示模板等更复杂的策略，可演化为
 * {@code Map<String, ToolPolicy>}，无需改动调用方。
 */
@ConfigurationProperties(prefix = "chat.hitl")
public class ToolPolicyProperties {

    /**
     * 需要审批才能执行的工具名白名单。null 或空集合 ≡ 全部工具走快路径（HITL 等同关闭）。
     * 默认空，由运维在 yaml 里按需开启。
     */
    private Set<String> requiredTools = Set.of();

    /**
     * 单条审批请求的超时时长。到期前端仍未回填决定时按 DECLINE 兜底。
     *
     * <p>默认 2 分钟，与 spring-ai-playground 的 ChatHumanQuestionHandler 对齐：
     * 既给用户充分阅读 / 决策时间，又避免一个被遗忘的弹窗把工具循环挂上整天。
     */
    private Duration timeout = Duration.ofMinutes(2);

    /**
     * {@code /chat/stream} 流级 SSE 保活心跳的节奏。{@code Duration.ZERO} 或负值 ≡ 关闭心跳。
     *
     * <p><b>为什么必须有</b>：HITL 人审或模型慢响应期间，SSE 流上可能出现较长无 data 帧的 quiet gap。
     * 反向代理（nginx 默认 {@code proxy_buffering on}、Cloudflare、各家云 LB）会把刚发的帧 hold 在
     * 自己的缓冲里不下发 —— 前端永远收不到完整事件（审批按钮渲染不出 / 答案迟迟不显示），
     * 最终超时整个会话死锁。
     *
     * <p><b>怎么解</b>：{@code ChatService.buildStreamFlux} 每 {@code heartbeatInterval} 往旁路 sink
     * 推一条 {@code {"type":"heartbeat"}} 的极小事件，把代理缓冲撑满 → 触发 flush → 前序帧也顺势
     * 冲下去。前端按 {@code type} 派发时把 {@code heartbeat} 直接忽略即可。
     *
     * <p><b>与 HITL gate 的分工</b>：周期性保活由本配置驱动（流管线）；{@code HitlToolCallingGate}
     * 另在 emit 每条 {@code approval_request} 后<u>立即</u>补一条 heartbeat（不等首次周期延迟），
     * 确保审批提示即时送达 —— 那一帧是无条件的，不受本配置影响。
     *
     * <p>默认 15s：兼容 nginx 60s 默认 idle、Cloudflare 100s 默认、AWS ALB 60s 默认，
     * 留够 4 倍冗余；同时一次 HITL 最多产生 ~8 条心跳（&lt; 1KB），对带宽零压力。
     */
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    /** 返回 {@code true} 表示该工具需要人审；{@code false} 走快路径。 */
    public boolean requiresApproval(String toolName) {
        return toolName != null
                && requiredTools != null
                && requiredTools.contains(toolName);
    }

    public Set<String> getRequiredTools() {
        return requiredTools;
    }

    public void setRequiredTools(Set<String> requiredTools) {
        this.requiredTools = requiredTools == null ? Set.of() : Set.copyOf(requiredTools);
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval == null
                ? Duration.ofSeconds(15)
                : heartbeatInterval;
    }
}
