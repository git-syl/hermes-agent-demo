package com.example.chat.api.dto;

/**
 * 非流式对话接口的返回体。
 *
 * @param content   模型最终回复正文（合并掉了思考链/思考过程）。
 * @param reasoning 模型的"深度思考"过程文本，可为空：
 *                  <ul>
 *                    <li>DeepSeek reasoner 系列：对应 OpenAI 风格响应里的 {@code reasoning_content} 字段；</li>
 *                    <li>Anthropic Claude 3.7+：对应 thinking 块（启用 {@code thinking="enabled"} 时）；</li>
 *                    <li>其他模型 / 不支持深度思考的情况：{@code null}。</li>
 *                  </ul>
 *                  多次思考片段会按出现顺序拼接成一段文本。
 * @param sessionId 服务端会话 id。客户端可以选择把它带回下一次请求实现服务端记忆，
 *                  也可以直接忽略（在 useServerMemory=false 模式下不会有任何副作用）。
 */
public record ChatResponse(String content, String reasoning, String sessionId) {}
