package com.example.chat.service;

import com.example.chat.api.dto.ChatEvent;

import java.util.List;
import java.util.Objects;

/**
 * 主 agent 与子代理在 HITL gate 上只差"事件标签"这一维：主 agent emit
 * {@code tool_call} / {@code approval_request} / {@code tool_result}（事件 {@code name}=null），
 * 子代理 emit {@code subagent_*}（事件 {@code name}=子代理名）。本接口把这层差异抽成策略，
 * 让 {@link HitlToolCallingGate} 的整条工具循环逻辑只写一份。
 *
 * <p>统一用主 agent 的参数顺序 {@code (requestId, toolCallId, toolName, arguments)}；
 * {@link Subagent} 实现内部重排到 {@link ChatEvent#subagentApprovalRequest} 的签名，
 * 消化两个工厂的参数顺序差异（主工厂 {@code (requestId, toolCallId, name, arguments)} vs
 * 子工厂 {@code (subagentName, requestId, toolCallId, toolName, arguments)}）。两者都把
 * {@code requestId} 落在事件 {@code data} 字段、单元素 {@link ChatEvent.ToolCallRef} 落在 {@code toolCalls}。
 *
 * <p><b>心跳不进工厂</b>：{@link ChatEvent#heartbeat()} 在主/子两处都是裸 {@code heartbeat} 帧
 * （不携带 {@code name}，前端按 {@code type} 派发时跳过，且肩负"撑满代理缓冲触发 flush"的职责），
 * 所以 gate 直接调 {@link ChatEvent#heartbeat()}，本接口刻意不含 heartbeat 方法 —— 避免子代理心跳
 * 误带 {@code name} 破坏前端派发与保活语义。
 *
 * <p>{@link #name()} 同时是日志 {@code subagent={}} 后缀的唯一真源（见 {@link HitlToolCallingGate}
 * 的 {@code subTrail} / {@code subParen}）。
 */
interface HitlEventFactory {

    /** 构造一条审批请求事件；{@code requestId} 落在事件 {@code data} 字段，前端原样回填 {@code POST /chat/approval}。 */
    ChatEvent approvalRequest(String requestId, String toolCallId, String toolName, String arguments);

    /** 构造一条本轮工具调用事件（已聚合的全部 toolCall）。 */
    ChatEvent toolCall(List<ChatEvent.ToolCallRef> calls);

    /** 构造一条工具执行结果事件。 */
    ChatEvent toolResult(List<ChatEvent.ToolResultRef> results);

    /** 工具调用事件的 type 串（{@code "tool_call"} / {@code "subagent_tool_call"}），用于失败日志。 */
    String toolCallEventType();

    /** 工具结果事件的 type 串（{@code "tool_result"} / {@code "subagent_tool_result"}），用于失败日志。 */
    String toolResultEventType();

    /**
     * 子代理名；主 agent 返回 {@code null}。是事件 {@code name} 字段与日志 {@code subagent={}}
     * 后缀的唯一真源。
     */
    String name();

    /** 主 agent 风格：{@code name=null}，委托 {@link ChatEvent#approvalRequest} / {@link ChatEvent#toolCall} / {@link ChatEvent#toolResult}。 */
    static HitlEventFactory mainAgent() {
        return MainAgent.INSTANCE;
    }

    /** 子代理风格：{@code name=subagentName}，委托 {@link ChatEvent#subagentApprovalRequest} 等 {@code subagent_*} 工厂。 */
    static HitlEventFactory subagent(String name) {
        return new Subagent(Objects.requireNonNull(name, "subagentName"));
    }

    /** 主 agent 实现。无状态单例。 */
    record MainAgent() implements HitlEventFactory {
        static final MainAgent INSTANCE = new MainAgent();

        @Override
        public ChatEvent approvalRequest(String requestId, String toolCallId, String toolName, String arguments) {
            return ChatEvent.approvalRequest(requestId, toolCallId, toolName, arguments);
        }

        @Override
        public ChatEvent toolCall(List<ChatEvent.ToolCallRef> calls) {
            return ChatEvent.toolCall(calls);
        }

        @Override
        public ChatEvent toolResult(List<ChatEvent.ToolResultRef> results) {
            return ChatEvent.toolResult(results);
        }

        @Override
        public String toolCallEventType() {
            return "tool_call";
        }

        @Override
        public String toolResultEventType() {
            return "tool_result";
        }

        @Override
        public String name() {
            return null;
        }
    }

    /**
     * 子代理实现。{@code name} 来自子代理 frontmatter，由 record 组件访问器满足 {@link #name()}。
     * {@code approvalRequest} 内部把参数重排到 {@link ChatEvent#subagentApprovalRequest} 的签名。
     */
    record Subagent(String name) implements HitlEventFactory {
        @Override
        public ChatEvent approvalRequest(String requestId, String toolCallId, String toolName, String arguments) {
            return ChatEvent.subagentApprovalRequest(name, requestId, toolCallId, toolName, arguments);
        }

        @Override
        public ChatEvent toolCall(List<ChatEvent.ToolCallRef> calls) {
            return ChatEvent.subagentToolCall(name, calls);
        }

        @Override
        public ChatEvent toolResult(List<ChatEvent.ToolResultRef> results) {
            return ChatEvent.subagentToolResult(name, results);
        }

        @Override
        public String toolCallEventType() {
            return "subagent_tool_call";
        }

        @Override
        public String toolResultEventType() {
            return "subagent_tool_result";
        }
    }
}
