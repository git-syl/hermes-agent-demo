package com.example.chat.sandbox;

import com.example.chat.config.SandboxProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.sandbox.Sandbox;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 {@code (userId, assistantId, sessionId)} 复用沙箱：同一对话的多轮请求共享一个 Docker
 * 容器，避免重复 pip install / 文件加载等开销。
 *
 * <h2>并发模型（一把锁，统一保护）</h2>
 * 单一同步原语 {@code synchronized(entry)}：每个 {@link Entry} 自带一把内置锁，acquire / close /
 * evict 操作 entry 状态时都在该锁内完成。entry 的 {@code refCount / lastAccessMs / evicted}
 * 都是普通字段，被该锁保护，无须 {@code AtomicXxx} / {@code volatile}。
 * <p>同 session 的多个并发请求<strong>不再被串行化</strong>——docker exec 本身线程安全，UI 一般也是
 * turn-based，串行化是过度防御。如果未来有同 session 真并发顺序敏感的场景，应由上层（业务）保证而不是
 * sandbox 层。
 *
 * <h2>生命周期</h2>
 * <ol>
 *   <li>{@link #acquire(SessionKey, List)}：找到则复用、{@code refCount++}；找不到则
 *       {@link SandboxFactory#create} 新建。</li>
 *   <li>{@link Lease#close()}：{@code refCount--} + 刷新 {@code lastAccess}。</li>
 *   <li>{@link #evictExpired()}：定时扫描，两种回收：
 *       <ul>
 *           <li><b>idle TTL</b>：{@code refCount=0 && idle>ttl} → remove + 异步 close</li>
 *           <li><b>maxLifetime 到点强清</b>：{@code age>maxLifetime} → remove + 异步 close，
 *               <b>不管 refCount</b>。当前正在执行的命令会失败一次，由用户重试。
 *               兜底场景：refCount 因 bug / 异常路径泄漏（idle/ttl 永远不触发），
 *               或 4h 沙箱状态老化（pip 包堆积 / tmpfs 写满 / 僵尸进程）。</li>
 *       </ul>
 *   </li>
 *   <li>容量满 → {@link #evictOneLruIdle()} 挑最老 idle entry 淘汰；全活跃则
 *       {@link SandboxCapacityExceededException}。</li>
 *   <li>{@link #shutdown()}（{@code @PreDestroy}）：兜底关闭全部沙箱。</li>
 * </ol>
 *
 * <h2>降级路径</h2>
 * {@code session-enabled=false} 或 {@code sessionId} 为空 → 完全绕过缓存，等价 per-request。
 *
 * <h2>集群提示</h2>
 * 本类只在单 JVM 内复用沙箱。多 Pod 部署时需要在 LB 层配 sticky session（如 Nginx
 * {@code hash $sticky_key consistent}），否则同 session 落到不同 Pod 会各建一个容器。
 */
@Component
public class SandboxSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxSessionManager.class);

    private final SandboxFactory factory;
    private final SandboxProperties props;
    private final ConcurrentHashMap<SessionKey, Entry> sessions = new ConcurrentHashMap<>();

    /** 异步关闭沙箱专用线程池：docker stop 默认 10s SIGKILL，不能阻塞调度 / 业务线程。 */
    private final ExecutorService closerPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sandbox-closer");
        t.setDaemon(true);
        return t;
    });

    public SandboxSessionManager(SandboxFactory factory, SandboxProperties props) {
        this.factory = factory;
        this.props = props;
    }

    /**
     * 申请沙箱。返回的 {@link Lease} 必须在 {@code try-with-resources}/{@code doFinally} 中 close。
     * <p>正确性：在 {@code synchronized(entry)} 内检查 {@code entry.evicted}，被 evict 标记的就 continue
     * 重试 —— 因为 evict 同时 {@code sessions.remove}，下一轮 {@code computeIfAbsent} 必然新建。
     */
    public Lease acquire(SessionKey key, List<Resource> skillDirs) {
        // 1) 灰度关 或 sessionId 缺失 → 一次性沙箱
        if (!props.isSessionEnabled() || key.sessionId() == null) {
            log.debug("Ephemeral sandbox (sessionEnabled={}, sessionId={})",
                    props.isSessionEnabled(), key.sessionId());
            return new EphemeralLease(factory.create(skillDirs));
        }

        // 2) 容量预检（best-effort）：达上限先 LRU 淘汰一个非活跃 entry
        if (!sessions.containsKey(key) && sessions.size() >= props.getMaxSessions()) {
            evictOneLruIdle();
            if (sessions.size() >= props.getMaxSessions()) {
                throw new SandboxCapacityExceededException(
                        "Sandbox session capacity (" + props.getMaxSessions() + ") exceeded");
            }
        }

        // 3) find-or-create + 抢用。重试上限 3 次足以覆盖 evict↔acquire 极端竞争。
        for (int attempt = 0; attempt < 3; attempt++) {
            Entry entry = sessions.computeIfAbsent(key, k -> {
                log.info("Creating new sandbox for session key={}", k);
                return new Entry(factory.create(skillDirs));
            });

            synchronized (entry) {
                if (entry.evicted) {
                    // 已被 evict 标记并 remove 出 sessions，下一轮 computeIfAbsent 会新建
                    log.debug("Stale entry detected for key={}, retrying acquire (attempt {})", key, attempt);
                    continue;
                }
                entry.refCount++;
                entry.lastAccessMs = System.currentTimeMillis();
                log.debug("Acquired sandbox for key={} (refCount={})", key, entry.refCount);
                return new SessionLease(key, entry);
            }
        }
        throw new RuntimeException("Failed to acquire sandbox for key=" + key + " after retries");
    }

    /**
     * 后台清理。两条触发线合并到同一处置：
     * <ul>
     *   <li><b>idle</b>：{@code refCount=0 && idle>ttl} — 正常回收</li>
     *   <li><b>aged</b>：{@code age>maxLifetime} — 到点强清，<b>不管 refCount</b>。当前命令失败由
     *       用户重试解决；换来"绝对不会有沙箱活过 maxLifetime + cleanupInterval"的强保证。</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${chat.sandbox.session-cleanup-interval-ms:60000}")
    public void evictExpired() {
        long now = System.currentTimeMillis();
        long ttlMs = props.getSessionTtl().toMillis();
        long maxLifeMs = props.getSessionMaxLifetime().toMillis();
        int evictedCount = 0;

        for (Map.Entry<SessionKey, Entry> e : sessions.entrySet()) {
            SessionKey key = e.getKey();
            Entry entry = e.getValue();

            synchronized (entry) {
                if (entry.evicted) {
                    continue;
                }
                long age = now - entry.createdMs;
                long idle = now - entry.lastAccessMs;
                boolean tooIdle = entry.refCount == 0 && idle > ttlMs;
                boolean tooOld = age > maxLifeMs;
                if (!tooIdle && !tooOld) {
                    continue;
                }

                entry.evicted = true;
                sessions.remove(key, entry);
                submitClose(() -> closeSilently(entry.sandbox));
                evictedCount++;

                if (tooOld && entry.refCount > 0) {
                    log.warn("Force-closing aged sandbox key={} (age {}ms > maxLifetime {}ms, refCount={} in-flight commands will fail)",
                            key, age, maxLifeMs, entry.refCount);
                } else if (tooOld) {
                    log.info("Evicting aged sandbox key={} (age {}ms > maxLifetime {}ms)", key, age, maxLifeMs);
                } else {
                    log.info("Evicting idle sandbox key={} (idle {}ms > ttl {}ms)", key, idle, ttlMs);
                }
            }
        }

        if (evictedCount > 0) {
            log.debug("evictExpired: removed={} remaining={}", evictedCount, sessions.size());
        }
    }

    /** 容量满时被 {@link #acquire} 触发的 LRU 淘汰（仅淘汰 refCount=0 的最老条目）。 */
    private void evictOneLruIdle() {
        Map.Entry<SessionKey, Entry> oldest = null;
        long oldestAccess = Long.MAX_VALUE;
        for (Map.Entry<SessionKey, Entry> e : sessions.entrySet()) {
            // 无锁快筛：lastAccessMs 在 synchronized 内被普通赋值，这里读到的可能是旧值，
            // 但 LRU 选择只需要"大致最老"即可，最终决定在锁内 double-check。
            Entry entry = e.getValue();
            if (entry.refCount == 0 && entry.lastAccessMs < oldestAccess) {
                oldest = e;
                oldestAccess = entry.lastAccessMs;
            }
        }
        if (oldest == null) {
            return;
        }
        SessionKey key = oldest.getKey();
        Entry entry = oldest.getValue();
        synchronized (entry) {
            if (entry.evicted || entry.refCount != 0) {
                return; // 状态已变（被其他线程抢用 / 已 evict），让上层抛 CapacityExceeded
            }
            entry.evicted = true;
            sessions.remove(key, entry);
            log.warn("Capacity LRU eviction key={} (size was >= maxSessions={})", key, props.getMaxSessions());
            submitClose(() -> closeSilently(entry.sandbox));
        }
    }

    /** 包装 closerPool.submit：shutdown 阶段池已关闭时直接降级为同步关闭，避免 RejectedExecutionException。 */
    private void submitClose(Runnable task) {
        try {
            closerPool.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            log.debug("closerPool already shut down, closing inline");
            task.run();
        }
    }

    /** 应用关停兜底：关闭全部沙箱并停掉线程池。 */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down SandboxSessionManager: closing {} sessions", sessions.size());
        sessions.forEach((k, e) -> closeSilently(e.sandbox));
        sessions.clear();
        closerPool.shutdown();
        try {
            if (!closerPool.awaitTermination(30, TimeUnit.SECONDS)) {
                closerPool.shutdownNow();
            }
        } catch (InterruptedException ie) {
            closerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 仅供监控/调试用：当前活跃 session 数。 */
    public int activeSessionCount() {
        return sessions.size();
    }

    private static void closeSilently(Sandbox sandbox) {
        try {
            sandbox.close();
        } catch (Exception e) {
            log.warn("Failed to close sandbox: {}", e.getMessage());
        }
    }

    // ====== 公共类型 ======

    /**
     * 会话身份。三段全部非空（{@code userKey/assistantKey} 用占位符），
     * {@code sessionId} 允许为 {@code null} —— 表示请求方未提供，走一次性沙箱不入缓存。
     */
    public record SessionKey(String userKey, String assistantKey, String sessionId) {

        /** 业务侧入参可能含 {@code null} 的 ID；统一标准化成稳定 key。 */
        public static SessionKey of(Long userId, Long assistantId, String sessionId) {
            String u = userId == null ? "anonymous" : userId.toString();
            String a = assistantId == null ? "default" : assistantId.toString();
            String s = (sessionId == null || sessionId.isBlank()) ? null : sessionId;
            return new SessionKey(u, a, s);
        }
    }

    /**
     * 沙箱租约。{@link #close()} 释放引用计数（per-session 模式）或直接关沙箱（一次性模式）。
     * <p>必须在 {@code try-with-resources} 或 {@code doFinally} 中 close。
     */
    public interface Lease extends AutoCloseable {
        Sandbox sandbox();

        @Override
        void close();
    }

    /** Per-session 入口持有的租约：close 仅 {@code refCount--} + 刷新 lastAccess，沙箱保活给后续请求。 */
    private final class SessionLease implements Lease {
        private final SessionKey key;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        SessionLease(SessionKey key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public Sandbox sandbox() {
            return entry.sandbox;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (entry) {
                entry.lastAccessMs = System.currentTimeMillis();
                entry.refCount--;
                log.debug("Released sandbox for key={} (refCount={})", key, entry.refCount);
            }
        }
    }

    /** 一次性租约：close 即关沙箱（灰度关 或 sessionId 缺失 时使用）。 */
    private static final class EphemeralLease implements Lease {
        private final Sandbox sandbox;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        EphemeralLease(Sandbox sandbox) {
            this.sandbox = sandbox;
        }

        @Override
        public Sandbox sandbox() {
            return sandbox;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeSilently(sandbox);
            }
        }
    }

    /**
     * 内部 entry。所有可变字段（refCount/lastAccessMs/evicted）全部由 {@code synchronized(entry)}
     * 保护，无需 atomic/volatile。
     */
    private static final class Entry {
        final Sandbox sandbox;
        final long createdMs;
        int refCount = 0;
        long lastAccessMs;
        /** 终态：沙箱已经 / 即将被 close，任何后续 acquire/evict 都应跳过。 */
        boolean evicted = false;

        Entry(Sandbox sandbox) {
            this.sandbox = sandbox;
            this.createdMs = System.currentTimeMillis();
            this.lastAccessMs = this.createdMs;
        }
    }

    /** 容量上限触发。 */
    public static class SandboxCapacityExceededException extends RuntimeException {
        public SandboxCapacityExceededException(String message) {
            super(message);
        }
    }
}
