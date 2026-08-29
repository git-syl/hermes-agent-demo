package com.example.chat.tools.external;

import com.example.chat.api.dto.ChatRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 一次外部工具调用的上下文。{@link ExternalToolPlatform#dispatch(ExternalToolInvocation)}
 * 的唯一入参。
 *
 * <ul>
 *   <li>{@link #definition()} —— 原始 {@link ChatRequest.ExternalTool} 定义（包含 platform
 *       与 platform 私有的 {@code config}）；platform 实现可按需读取。</li>
 *   <li>{@link #toolName()} —— LLM 选中的工具名（即 {@code definition.name()}），
 *       单独提供方便 log 与转发体直接使用。</li>
 *   <li>{@link #arguments()} —— LLM 产生的工具参数 JSON 字符串。</li>
 *   <li>{@link #toolContext()} —— 每请求上下文（userId / apiKey 等）；可空。
 *       禁止写入暴露给 LLM 的 body，建议走 header 或服务端私有通道传递。</li>
 * </ul>
 */
public record ExternalToolInvocation(
        ChatRequest.ExternalTool definition,
        String toolName,
        String arguments,
        @Nullable ToolContext toolContext
) {
}
