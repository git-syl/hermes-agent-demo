package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for tool-execution sandbox. The sandbox isolates user-uploaded
 * skill scripts (shell, python, ...) from the host so that they cannot read or
 * write host files outside their workspace, exec arbitrary binaries on the host,
 * or escape resource limits.
 *
 * <p>Bound to {@code chat.sandbox.*} in {@code application.yaml}.
 */
@ConfigurationProperties(prefix = "chat.sandbox")
public class SandboxProperties {

    /** Backend implementation. */
    public enum Mode {
        /** {@code DockerSandbox} — real process/fs/network isolation via Testcontainers. Recommended for prod. */
        DOCKER,
        /** {@code LocalSandbox} — tempdir cwd only, NO process isolation. Dev fallback when Docker is unavailable. */
        LOCAL
    }

    private Mode mode = Mode.DOCKER;

    /** Docker image used in {@link Mode#DOCKER}. Should contain at least {@code bash} and {@code python}. */
    private String image = "ghcr.io/spring-ai-community/agents-runtime:latest";

    /**
     * Wall-clock limit per {@code Bash} tool invocation.
     * <p>双重语义：
     * <ul>
     *     <li>LLM 未传 timeout 时，作为默认超时。</li>
     *     <li>LLM 传入 timeout 时，作为硬上限——超过此值会被静默夹紧，防止 prompt-injection
     *         把单次执行拖到运维允许之上的时长。</li>
     * </ul>
     */
    private long execTimeoutMs = 120_000L;

    // ====== per-session 沙箱复用（默认关闭，灰度时打开） ======

    /**
     * 灰度开关：开启后按 {@code (userId, assistantId, sessionId)} 复用沙箱；关闭则维持 per-request。
     * <p>关闭时 {@code SandboxSessionManager} 所有路径自动退化为一次性沙箱，行为等价旧版。
     */
    private boolean sessionEnabled = false;

    /** 沙箱闲置多久后被后台清理（refCount=0 且 now - lastAccess > 此值）。 */
    private Duration sessionTtl = Duration.ofMinutes(30);

    /** 后台清理任务轮询间隔。注意：实际生效的是 {@code session-cleanup-interval-ms}（@Scheduled 限制）。 */
    private Duration sessionCleanupInterval = Duration.ofSeconds(60);

    /**
     * 沙箱绝对最大寿命：从创建时刻起超过此值就由后台清理强 close，<b>不管 refCount</b>。
     * 兜底场景：
     * <ul>
     *     <li>refCount 因 bug / 客户端异常断连等原因泄漏（永远 &gt; 0），idle/ttl 路径失效</li>
     *     <li>长生命周期沙箱积压：pip 包堆积 / tmpfs 写满 / 僵尸进程残留</li>
     * </ul>
     * 触发时正在执行的命令会失败一次，由用户重试解决（重试会自动建新沙箱）。
     */
    private Duration sessionMaxLifetime = Duration.ofHours(4);

    /** 全局活跃沙箱条数上限。达到后先按 LRU 淘汰一个非活跃条目；仍超限则拒绝新请求。 */
    private int maxSessions = 200;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public long getExecTimeoutMs() {
        return execTimeoutMs;
    }

    public void setExecTimeoutMs(long execTimeoutMs) {
        this.execTimeoutMs = execTimeoutMs;
    }

    public boolean isSessionEnabled() {
        return sessionEnabled;
    }

    public void setSessionEnabled(boolean sessionEnabled) {
        this.sessionEnabled = sessionEnabled;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getSessionCleanupInterval() {
        return sessionCleanupInterval;
    }

    public void setSessionCleanupInterval(Duration sessionCleanupInterval) {
        this.sessionCleanupInterval = sessionCleanupInterval;
    }

    public Duration getSessionMaxLifetime() {
        return sessionMaxLifetime;
    }

    public void setSessionMaxLifetime(Duration sessionMaxLifetime) {
        this.sessionMaxLifetime = sessionMaxLifetime;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }
}
