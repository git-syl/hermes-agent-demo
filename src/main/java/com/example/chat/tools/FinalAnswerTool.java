package com.example.chat.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 最终输出协议工具（"Final Answer" 协议）：Skill 已备好完整结构化结果（典型为 JSON）时，
 * 模型调用本工具把 payload 整段传入，而不是把 JSON 写进正文。
 *
 * <p>核心机制：{@code @Tool(returnDirect = true)}。{@code ToolCallingAdvisor} 检测到
 * {@code ToolExecutionResult.returnDirect()==true} 时直接打破工具循环，把工具返回值包装成
 * {@code ChatResponse} 单 chunk 返回，不再发起下一轮 LLM（省掉模型逐字重述 payload 的耗时与 token）。
 * 同步 / 流式两种调用方式均受 Spring AI 原生支持。
 *
 * <p>本工具在沙箱开启时由 {@code ChatService} 注入（skills / 显式 Claude 模式任一触发沙箱，
 * 见 {@code ChatService.needSandbox}）；无沙箱的纯文本对话不暴露。FinalAnswer 并非只能用于
 * Skills —— 任何产生最终结构化 payload 的场景均可经此通道原样下发。
 *
 * @see <a href="https://docs.spring.io/spring-ai/reference/2.0/api/tools.html#_return_direct">Spring AI – Return Direct</a>
 */
@Component
public class FinalAnswerTool {

    public static final String FINAL_ANSWER = "FinalAnswer";

    // @formatter:off
    @Tool(name = FINAL_ANSWER, returnDirect = true, description = """
        Protocol tool: forwards a final structured payload to the user verbatim and ends the
        turn in one shot. This is NOT a generic "I'm done" tool — it is a delivery channel
        that a Skill or subagent/tool must opt into.

        WHEN to call (ALL of the following must hold):
        (1) A Skill's instructions (or an equivalent producer) explicitly designate FinalAnswer
            as the delivery channel for its result.
        (2) You have just produced that result (e.g., ran a Skill script and have its raw stdout
            in hand).
        (3) The result is already the final, frontend-ready payload (typically a JSON object).
        (4) You can pass it byte-for-byte without any edits.

        WHEN NOT to call:
        - Ordinary natural-language replies, greetings, summaries, or explanations.
        - JSON or any text you composed yourself, even if it looks like a "final answer".
        - Output from a generic shell command or built-in tool that was not designated
          for FinalAnswer.
        - Any case where condition (1) is unclear — when in doubt, reply with normal text.

        Payload contract:
        - Pass only the raw result text. No markdown, no code fences, no prose, no greetings,
          no translation, no extra fields.
        - Do not reformat, re-indent, re-escape, compress, or otherwise alter a single byte.

        Termination contract:
        - Exactly one FinalAnswer call ends the turn. Do not emit any text or any other tool
          call before or after it within the same turn.
        """)
    public String finalAnswer(
        @ToolParam(description = """
            The verbatim stdout of the Skill's script. Must be passed byte-for-byte: no
            surrounding prose, no code fences, no reformatting, no re-escaping, and no
            model-authored additions.""")
        String payload) {
        // @formatter:on

        // returnDirect=true：ToolCallAdvisor 把这条响应直接作为 ChatResponse 的
        // generation content 下发给客户端，跳过下一轮 LLM。
        return payload == null ? "" : payload;
    }
}
