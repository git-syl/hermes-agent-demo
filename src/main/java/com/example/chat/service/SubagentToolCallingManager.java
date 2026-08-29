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
 * 子代理的 {@link ToolCallingManager} 装饰器：薄壳，把整条工具循环（SSE 事件旁路 + HITL 审批 gate）
 * 委托给共享的 {@link HitlToolCallingGate}，自身只持有 {@code delegate} 并用
 * {@link HitlEventFactory#subagent(String)} 风格 emit {@code subagent_tool_call} /
 * {@code subagent_approval_request} / {@code subagent_tool_result} 事件（事件 {@code name}=子代理名）。
 *
 * <p>子代理遵循<b>同一份</b> {@code chat.hitl.required-tools} 策略 —— 配了 {@code Bash} 要审批，
 * 子代理内部的 {@code Bash} 也要审批。{@code bypassApproval} 跟随主请求（{@code ChatRequest.bypassApproval}
 * 对子代理同样生效）。回填通道与主 agent 共用：同一 {@code POST /chat/approval} + 同一
 * {@link ApprovalRegistry}（requestId 全局唯一，不区分主/子）。
 *
 * <p>fail-safe / 线程模型 / 事件顺序等设计见 {@link HitlToolCallingGate} 类文档。
 * 主 agent 对应实现见 {@link ObservableToolCallingManager}。
 */
class SubagentToolCallingManager implements ToolCallingManager {

    private final ToolCallingManager delegate;
    private final HitlToolCallingGate gate;

    SubagentToolCallingManager(ToolCallingManager delegate, Sinks.Many<ChatEvent> sink, String subagentName,
                               ToolPolicyProperties policy, ApprovalRegistry approvals, boolean bypassApproval) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.gate = new HitlToolCallingGate(sink, policy, approvals, bypassApproval,
                HitlEventFactory.subagent(Objects.requireNonNull(subagentName, "subagentName")));
    }

    /**
     * 用一个新的 delegate 重建本装饰器（HITL gate / 子代理名原样保留）。用于把官方
     * {@code DefaultToolCallingManager} 换成带限额配置的实例（见 {@code ChatService.withToolCallLimit}）。
     * 本类不可变，故返回新实例而非原地改。
     */
    SubagentToolCallingManager withDelegate(ToolCallingManager newDelegate) {
        Objects.requireNonNull(newDelegate, "newDelegate");
        return new SubagentToolCallingManager(newDelegate, this.gate);
    }

    /** 包内构造：复用已建好的 gate（HITL 策略/审批/事件工厂/子代理名不变），只换 delegate。 */
    private SubagentToolCallingManager(ToolCallingManager delegate, HitlToolCallingGate gate) {
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
