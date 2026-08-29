package com.example.chat.service;

import com.example.chat.api.dto.ChatEvent;
import com.example.chat.config.ToolPolicyProperties;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Objects;

/**
 * 主 agent 的 {@link ToolCallingManager} 装饰器：薄壳，把整条工具循环（SSE 事件旁路 + HITL 审批 gate）
 * 委托给共享的 {@link HitlToolCallingGate}，自身只持有 {@code delegate} 并用 {@link HitlEventFactory#mainAgent()}
 * 风格 emit {@code tool_call} / {@code approval_request} / {@code tool_result} 事件（事件 {@code name}=null）。
 *
 * <p>fail-safe / 线程模型 / 事件顺序 / GA 旁路等设计见 {@link HitlToolCallingGate} 类文档。
 * 子代理对应实现见 {@link SubagentToolCallingManager}。
 */
class ObservableToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final HitlToolCallingGate gate;

    /**
     * 全装配（生产入口）：sink 收事件、policy 决定哪些工具需要审批、registry 协调审批回填。
     * 三者都不能为 null —— 半装配会在生产里悄悄绕过 HITL，宁可启动报 NPE 也别让 REQUIRED
     * 工具静默执行。构造签名稳定不变：{@code ChatService} 直接 {@code new}，无 Spring 代理。
     */
    ObservableToolCallingManager(ToolCallingManager delegate, Sinks.Many<ChatEvent> sink,
                                 ToolPolicyProperties policy, ApprovalRegistry approvals,
                                 boolean bypassApproval) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.gate = new HitlToolCallingGate(sink, policy, approvals, bypassApproval, HitlEventFactory.mainAgent());
    }

    /**
     * 用一个新的 delegate 重建本装饰器（HITL gate 配置原样保留）。用于在不改动本类 HITL/旁路
     * 逻辑的前提下，把官方 {@code DefaultToolCallingManager} 换成带限额配置的实例
     * （见 {@code ChatService.withToolCallLimit}）。本类不可变，故返回新实例而非原地改。
     */
    ObservableToolCallingManager withDelegate(ToolCallingManager newDelegate) {
        Objects.requireNonNull(newDelegate, "newDelegate");
        return new ObservableToolCallingManager(newDelegate, this.gate);
    }

    /** 包内构造：复用已建好的 gate（HITL 策略/审批/事件工厂不变），只换 delegate。 */
    private ObservableToolCallingManager(ToolCallingManager delegate, HitlToolCallingGate gate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.gate = gate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        return gate.executeToolCalls(prompt, chatResponse, delegate);
    }
}
