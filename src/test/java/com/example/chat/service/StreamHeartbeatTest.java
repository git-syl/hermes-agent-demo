package com.example.chat.service;

import com.example.chat.api.dto.ChatEvent;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StreamHeartbeat} 周期性保活心跳：每 interval 往 sink 推一条裸 heartbeat，dispose 后立即停。
 * 从 {@link HitlToolCallingGate} 迁出的同款 {@code Flux.interval} 机制 —— gate 改为 emit
 * approval_request 后立即补一条；周期性保活移到 /chat/stream 流管线（本类）。
 */
class StreamHeartbeatTest {

    @Test
    void emitsPeriodicHeartbeatsAndStopsOnDispose() throws InterruptedException {
        Sinks.Many<ChatEvent> sink = Sinks.many().replay().all();
        CopyOnWriteArrayList<ChatEvent> events = new CopyOnWriteArrayList<>();
        sink.asFlux().subscribe(events::add);

        Disposable hb = StreamHeartbeat.start(sink, Duration.ofMillis(50));
        try {
            // 50ms 间隔，断言下限取 2 给 CI 调度抖动留宽
            long deadlineNanos = System.nanoTime() + 5_000_000_000L;
            while (events.size() < 2 && System.nanoTime() < deadlineNanos) {
                Thread.sleep(5);
            }
            assertThat(events).hasSizeGreaterThanOrEqualTo(2);
            assertThat(events).allSatisfy(e -> {
                assertThat(e.type()).isEqualTo("heartbeat");
                assertThat(e.name()).isNull(); // 裸事件不带 name
            });

            hb.dispose();
            // 给在飞的心跳一点时间落地，之后再计数，规避 capture→dispose 之间的竞态
            Thread.sleep(60);
            int afterDisposeCount = events.size();

            // dispose 后不再有新心跳 —— 再睡 150ms（= 3 个间隔）完全不涨
            Thread.sleep(150);
            assertThat(events.size()).isEqualTo(afterDisposeCount);
        } finally {
            hb.dispose();
        }
    }

    @Test
    void noOpWhenIntervalNonPositive() {
        Sinks.Many<ChatEvent> sink = Sinks.many().replay().all();
        CopyOnWriteArrayList<ChatEvent> events = new CopyOnWriteArrayList<>();
        sink.asFlux().subscribe(events::add);

        // 零 / null / 负值都应返回已 dispose 的 no-op，不发任何心跳
        Disposable hb = StreamHeartbeat.start(sink, Duration.ZERO);
        assertThat(hb.isDisposed()).isTrue();
        assertThat(StreamHeartbeat.start(sink, null).isDisposed()).isTrue();
        assertThat(StreamHeartbeat.start(sink, Duration.ofMillis(-1)).isDisposed()).isTrue();
        assertThat(events).isEmpty();
        hb.dispose();
    }
}
