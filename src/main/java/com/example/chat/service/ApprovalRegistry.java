package com.example.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Human-in-the-Loop 审批的"待回填请求"注册表。单例，跨请求共享。
 *
 * <p><b>用法</b>：
 * <ol>
 *   <li>工具循环命中需审批的 toolCall → {@link #register(Duration)} 拿到
 *       {@code (requestId, future)}；</li>
 *   <li>把 {@code requestId} 通过 SSE 推给前端；</li>
 *   <li>当前线程 {@code future.get()} 阻塞（已 {@code orTimeout} 兜底）；</li>
 *   <li>前端 {@code POST /chat/approval} 携带 {@code requestId} 回填决定 →
 *       {@link #complete(String, Decision)} 唤醒等待线程。</li>
 * </ol>
 *
 * <p><b>fail-safe 不变式</b>：任何"非 APPROVE"路径都不允许工具执行。
 * <ul>
 *   <li>超时 → {@code future} 完成为 {@link java.util.concurrent.TimeoutException}，
 *       调用方按 {@link Decision#DECLINE} 处理；</li>
 *   <li>requestId 不存在（前端伪造、registry 重启）→ {@link #complete} 返回
 *       {@code false}，控制器据此回 4xx，不影响其它待审批；</li>
 *   <li>线程被中断 → 调用方按 DECLINE 处理（见 {@code ObservableToolCallingManager}）。</li>
 * </ul>
 *
 * <p><b>内存管理</b>：每个 future 都挂 {@code whenComplete} 钩子主动从 map 移除，
 * 三种终态（complete / cancel / timeout）都会触发清理，没有泄漏路径。
 *
 * <p><b>线程安全</b>：{@link ConcurrentHashMap} + {@link CompletableFuture} 全程无锁。
 * {@code complete} 与终态 {@code whenComplete} 由 {@code CompletableFuture} 自身串行化，
 * 不会出现"先 remove 又被 put 回去"的乱序。
 */
@Component
public class ApprovalRegistry {

    /** 用户对一次工具调用的审批决定。 */
    public enum Decision { APPROVE, DECLINE }

    private static final Logger log = LoggerFactory.getLogger(ApprovalRegistry.class);

    private final Map<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    /**
     * 注册一条新的待审批请求。返回的 {@code Pending} 同时持有 {@code requestId}（用于
     * 透传到前端）和 {@code future}（用于工具循环阻塞等待）。
     *
     * @param timeout 超时时长。到期 future 完成为 {@link java.util.concurrent.TimeoutException}。
     *                必须 &gt; 0，否则 {@link CompletableFuture#orTimeout} 抛 IllegalArgumentException。
     */
    public Pending register(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be > 0, got " + timeout);
        }
        String id = UUID.randomUUID().toString();
        CompletableFuture<Decision> f = new CompletableFuture<>();
        // orTimeout 返回的就是自身 future；超时时以 TimeoutException 完成。
        // 调用方 catch ExecutionException / TimeoutException 一律按 DECLINE 处理。
        f.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        pending.put(id, f);
        // 三种终态（complete / cancel / timeout）都会跑到这里，统一清理避免泄漏。
        f.whenComplete((decision, error) -> pending.remove(id));
        log.debug("hitl.approval.registered requestId={} timeoutMs={}", id, timeout.toMillis());
        return new Pending(id, f);
    }

    /**
     * 用前端回填的决定完成对应的 future。
     *
     * @return {@code true} 表示成功唤醒等待线程；{@code false} 表示 requestId 不存在或
     *         future 已被 timeout/其它路径完成（前端可据此提示"审批已超时"）。
     */
    public boolean complete(String requestId, Decision decision) {
        if (requestId == null || decision == null) {
            return false;
        }
        CompletableFuture<Decision> f = pending.get(requestId);
        if (f == null) {
            log.info("hitl.approval.miss requestId={} reason=unknown-or-completed", requestId);
            return false;
        }
        boolean ok = f.complete(decision);
        if (ok) {
            log.info("hitl.approval.resolved requestId={} decision={}", requestId, decision);
        } else {
            log.info("hitl.approval.miss requestId={} reason=already-completed", requestId);
        }
        return ok;
    }

    /** 仅用于观测 / 健康检查 / 测试，不做语义承诺。 */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * 注册结果。{@code requestId} 透给前端，{@code future} 留给工具循环阻塞等待。
     * record 不可变，安全跨线程传递。
     */
    public record Pending(String requestId, CompletableFuture<Decision> future) {}
}
