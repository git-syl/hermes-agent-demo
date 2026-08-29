package com.example.chat.service;

import com.example.chat.api.dto.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * SSE 流级周期性保活心跳。每 {@code interval} 往 sink 推一条裸 {@link ChatEvent#heartbeat()} 帧，
 * 把 nginx / Cloudflare / 各家云 LB 的代理缓冲撑满触发 flush，避免长阻塞期（HITL 人审、模型慢响应
 * quiet gap）流上无 data 帧时中间层 hold 住前序事件、前端超时。
 *
 * <p><b>与 {@link HitlToolCallingGate} 的分工</b>：gate 在 emit 每条 {@code approval_request} 后
 * <u>立即</u>补一条 heartbeat 确保审批提示即时送达（不等首次周期延迟）；本类负责<u>周期性</u>保活。
 * 两者共用同一 sink（生产环境即 {@code /chat/stream} 的 {@code toolEventSink}），心跳帧都裸
 * {@code heartbeat}（不带 name），前端按 type 派发时跳过。
 *
 * <p><b>生命周期</b>：返回的 {@link Disposable} 由调用方在流结束（complete/error/cancel）时 dispose，
 * 取消订阅即停 —— 否则 {@code Flux.interval} 是无限的，会一直占着 {@code Schedulers.parallel()} 线程。
 *
 * <p><b>escape hatch</b>：{@code interval} 为 null / 零 / 负值时返回已 dispose 的 no-op，
 * 保留给"明确知道不走代理、不需要保活"的部署。
 */
final class StreamHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(StreamHeartbeat.class);

    private StreamHeartbeat() {}

    /**
     * 启动周期性心跳订阅，往 {@code sink} 推裸 {@code heartbeat} 帧。
     *
     * @param sink     SSE 事件 sink（与主响应流 merge 的旁路 sink）。
     * @param interval 心跳间隔；null / 零 / 负值 → 返回 no-op 已 dispose 的 Disposable。
     * @return 订阅句柄；流结束时应 {@link Disposable#dispose()} 取消。
     */
    static Disposable start(Sinks.Many<ChatEvent> sink, Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return Disposables.disposed();
        }
        return Flux.interval(interval, interval, Schedulers.parallel())
                .subscribe(
                        tick -> sink.tryEmitNext(ChatEvent.heartbeat()),
                        err -> log.debug("stream.heartbeat.err {}", err.toString()));
    }
}
