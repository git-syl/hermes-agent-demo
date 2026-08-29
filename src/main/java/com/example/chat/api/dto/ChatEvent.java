package com.example.chat.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 流式响应的结构化事件，对应 SSE 的一条 data。{@code type} 取值：
 * <ul>
 *   <li>{@code token} / {@code reasoning} —— 文本 / 思考增量，在 {@link #data}。</li>
 *   <li>{@code tool_call} / {@code tool_result} —— 工具调用与结果，在 {@link #toolCalls} / {@link #toolResults}。</li>
 *   <li>{@code approval_request} —— 命中 HITL 白名单需人审；{@link #data}=requestId（回填
 *       {@code POST /chat/approval}），{@link #toolCalls} 单元素载明被审工具；超时未回填按 DECLINE 兜底。</li>
 *   <li>{@code finish} / {@code error} —— 结束（{@link #reason}，可选 {@link #usage}）/ 异常（{@link #data}）。</li>
 *   <li>{@code heartbeat} —— SSE 保活帧，无业务载荷，前端直接忽略。</li>
 * </ul>
 *
 * <p><b>子代理事件</b>（{@code subagent_start / subagent_token / subagent_reasoning / subagent_tool_call /
 * subagent_approval_request / subagent_tool_result / subagent_finish}）：主 agent 通过 {@code Task} 委派
 * 子代理时，子代理执行过程走同一 SSE 流，{@link #name} 统一携带子代理名，字段语义与主 agent 对应事件一致，
 * 审批回填与主 agent 共用 {@code /chat/approval} + {@code ApprovalRegistry}。主 agent 调 {@code Task}
 * 本身仍走 {@code tool_call}/{@code tool_result}。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(
        String type,
        String data,
        String name,
        Map<String, Object> args,
        String reason,
        Map<String, Object> usage,
        List<ToolCallRef> toolCalls,
        List<ToolResultRef> toolResults
) {

    public record ToolCallRef(String id, String name, String arguments) {}

    public record ToolResultRef(String id, String name, String result) {}

    public static ChatEvent token(String text) {
        return new ChatEvent("token", text, null, null, null, null, null, null);
    }

    public static ChatEvent reasoning(String text) {
        return new ChatEvent("reasoning", text, null, null, null, null, null, null);
    }

    public static ChatEvent toolCall(List<ToolCallRef> calls) {
        return new ChatEvent("tool_call", null, null, null, null, null, calls, null);
    }

    public static ChatEvent toolResult(List<ToolResultRef> results) {
        return new ChatEvent("tool_result", null, null, null, null, null, null, results);
    }

    /**
     * 一条 HITL 待审批事件：{@code requestId} 放 {@link #data}（前端原样回填
     * {@code POST /chat/approval}），被审工具复用 {@link ToolCallRef} 单元素列表，解析与
     * {@code tool_call} 同形，无须新增 DTO。
     */
    public static ChatEvent approvalRequest(String requestId, String toolCallId, String name, String arguments) {
        return new ChatEvent("approval_request", requestId, null, null, null, null,
                List.of(new ToolCallRef(toolCallId, name, arguments)), null);
    }

    public static ChatEvent finish(String reason, Map<String, Object> usage) {
        return new ChatEvent("finish", null, null, null, reason, usage, null, null);
    }

    public static ChatEvent error(String message) {
        return new ChatEvent("error", message, null, null, null, null, null, null);
    }

    /** SSE 保活帧，无业务载荷；HITL 长阻塞期间定时推送撑满代理缓冲触发 flush。 */
    public static ChatEvent heartbeat() {
        return new ChatEvent("heartbeat", null, null, null, null, null, null, null);
    }

    /**
     * 子代理启动事件。{@code name} 是子代理 frontmatter 里的 name（模型可见标识），
     * {@code prompt} 是主 agent 通过 Task 工具传给子代理的任务描述，放 {@link #data} 字段。
     */
    public static ChatEvent subagentStart(String name, String prompt) {
        return new ChatEvent("subagent_start", prompt, name, null, null, null, null, null);
    }

    /** 子代理流式输出增量；{@code text} 放 {@link #data}，{@code name} 标识来源子代理。 */
    public static ChatEvent subagentToken(String name, String text) {
        return new ChatEvent("subagent_token", text, name, null, null, null, null, null);
    }

    /** 子代理"深度思考"增量，与 {@code reasoning} 同义，仅来源是子代理（仅推理类模型产生）。 */
    public static ChatEvent subagentReasoning(String name, String text) {
        return new ChatEvent("subagent_reasoning", text, name, null, null, null, null, null);
    }

    /** 子代理内部决定调用工具；复用 {@link #toolCalls} 字段，{@link #name} 标识来源子代理。 */
    public static ChatEvent subagentToolCall(String name, List<ToolCallRef> calls) {
        return new ChatEvent("subagent_tool_call", null, name, null, null, null, calls, null);
    }

    /** 子代理内部命中 HITL 白名单需人审，与 {@link #approvalRequest} 同义，仅 {@code name}=子代理名。 */
    public static ChatEvent subagentApprovalRequest(String subagentName, String requestId,
                                                    String toolCallId, String toolName, String arguments) {
        return new ChatEvent("subagent_approval_request", requestId, subagentName, null, null, null,
                List.of(new ToolCallRef(toolCallId, toolName, arguments)), null);
    }

    /** 子代理内部工具执行结束；复用 {@link #toolResults} 字段，{@link #name} 标识来源子代理。 */
    public static ChatEvent subagentToolResult(String name, List<ToolResultRef> results) {
        return new ChatEvent("subagent_tool_result", null, name, null, null, null, null, results);
    }

    /** 子代理结束；{@code reason} 放 finish reason（STOP / TOOL_CALLS 等）。 */
    public static ChatEvent subagentFinish(String name, String reason) {
        return new ChatEvent("subagent_finish", null, name, null, reason, null, null, null);
    }
}
