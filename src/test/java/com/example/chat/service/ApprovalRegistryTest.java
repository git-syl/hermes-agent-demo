package com.example.chat.service;

import com.example.chat.service.ApprovalRegistry.Decision;
import com.example.chat.service.ApprovalRegistry.Pending;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 {@link ApprovalRegistry} 的三类不变式：
 * <ol>
 *   <li>register → complete(APPROVE) → future 解出 APPROVE，且条目自清理；</li>
 *   <li>register → 等待超时 → future 以 {@link TimeoutException} 完成，条目自清理；</li>
 *   <li>未知 requestId / 重复 complete → 返回 {@code false}，不破坏已就位的等待。</li>
 * </ol>
 */
class ApprovalRegistryTest {

    private final ApprovalRegistry registry = new ApprovalRegistry();

    @Test
    void approveCompletesFutureAndCleansUp() throws Exception {
        Pending p = registry.register(Duration.ofSeconds(5));
        assertThat(registry.pendingCount()).isEqualTo(1);

        boolean ok = registry.complete(p.requestId(), Decision.APPROVE);
        assertThat(ok).isTrue();
        assertThat(p.future().get(1, TimeUnit.SECONDS)).isEqualTo(Decision.APPROVE);

        // whenComplete 钩子是异步触发的，最长等 500ms 让自清理跑完
        waitUntil(() -> registry.pendingCount() == 0, 500);
        assertThat(registry.pendingCount()).isZero();
    }

    @Test
    void declineCompletesFutureAndCleansUp() throws Exception {
        Pending p = registry.register(Duration.ofSeconds(5));

        boolean ok = registry.complete(p.requestId(), Decision.DECLINE);
        assertThat(ok).isTrue();
        assertThat(p.future().get(1, TimeUnit.SECONDS)).isEqualTo(Decision.DECLINE);
        waitUntil(() -> registry.pendingCount() == 0, 500);
        assertThat(registry.pendingCount()).isZero();
    }

    @Test
    void timeoutFiresTimeoutExceptionAndCleansUp() {
        Pending p = registry.register(Duration.ofMillis(50));

        // ExecutionException 包了 TimeoutException —— ObservableToolCallingManager.awaitDecision
        // 会把这一类异常一律按 DECLINE 兜底。
        assertThatThrownBy(() -> p.future().get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        waitUntil(() -> registry.pendingCount() == 0, 500);
        assertThat(registry.pendingCount()).isZero();
    }

    @Test
    void completeReturnsFalseForUnknownRequestId() {
        boolean ok = registry.complete("not-a-real-id", Decision.APPROVE);
        assertThat(ok).isFalse();
    }

    @Test
    void completeReturnsFalseOnSecondCallForSameRequestId() {
        Pending p = registry.register(Duration.ofSeconds(5));
        assertThat(registry.complete(p.requestId(), Decision.APPROVE)).isTrue();
        // 第二次回填：future 已完成，complete 返回 false；不影响首次结果。
        assertThat(registry.complete(p.requestId(), Decision.DECLINE)).isFalse();
    }

    @Test
    void registerRejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeRejectsNullArgs() {
        Pending p = registry.register(Duration.ofSeconds(5));
        assertThat(registry.complete(null, Decision.APPROVE)).isFalse();
        assertThat(registry.complete(p.requestId(), null)).isFalse();
    }

    /** 轮询直到条件成立或超时；避免依赖 sleep + 固定时长的脆弱断言。 */
    private static void waitUntil(java.util.function.BooleanSupplier cond, long maxMillis) {
        long deadline = System.nanoTime() + maxMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(10); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); return;
            }
        }
    }
}
