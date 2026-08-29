package com.example.chat.service;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

/**
 * 从流式 / 非流式 {@link org.springframework.ai.chat.model.ChatResponse} 的
 * {@link AssistantMessage} 里抽取"深度思考(reasoning)"与"正文(text)"，统一各厂商的差异。
 *
 * <p>各厂商暴露 reasoning 的方式不同：
 * <ul>
 *   <li>Anthropic：thinking 文本与正文都走 {@code content}，靠 metadata 区分。
 *       流式 thinking 块 {@code metadata.thinking == TRUE}；
 *       非流式 thinking 块 {@code metadata.signature} 存在。</li>
 *   <li>DeepSeek：reasoning 在独立字段
 *       {@link DeepSeekAssistantMessage#getReasoningContent()}，{@code content}
 *       永远是正文，二者在单个 chunk 里通常互斥。</li>
 * </ul>
 *
 * <p>主 agent（{@link ChatService#toEvents} 流式 / {@link ChatService#extractReasoningAndText}
 * 非流式）与子代理（{@link SandboxSubagentExecutor}）共用本类，避免分类逻辑两处维护、各自漂移。
 *
 * <p>本类只做"取值 + 分类"，<strong>不做空白过滤</strong>——同一份原始文本返回给调用方，
 * 由调用方按场景决定用 {@code !isEmpty()}（流式，保留换行 / 纯空白 chunk，避免代码段被挤成一行）
 * 还是 {@code StringUtils.hasText}（非流式整段拼接，过滤纯空白）。
 *
 * @see ChatService#toEvents
 * @see ChatService#extractReasoningAndText
 * @see SandboxSubagentExecutor
 */
public final class ReasoningExtractor {

	private ReasoningExtractor() {
	}

	/**
	 * 是否为"思考块"。
	 * <p>仅 Anthropic 会返回 {@code true}（thinking metadata）。DeepSeek 的 reasoning 在独立字段、
	 * {@code content} 永远是正文，本方法对 DeepSeek 恒返回 {@code false}。
	 */
	public static boolean isReasoning(AssistantMessage msg) {
		Map<String, Object> meta = msg.getMetadata();
		if (meta == null) {
			return false;
		}
		// 流式 thinking 块：metadata.thinking == TRUE
		// 非流式 thinking 块：metadata.signature 存在
		return Boolean.TRUE.equals(meta.get("thinking")) || meta.get("signature") != null;
	}

	/**
	 * 抽取思考文本：Anthropic thinking 块的 {@code content}，或 DeepSeek 的 {@code reasoning_content}。
	 * @return 思考文本（可能为空串）；非思考块且非 DeepSeek 返回 {@code null}
	 */
	public static @Nullable String

	reasoningOf(AssistantMessage msg) {
		if (isReasoning(msg)) {
			// Anthropic thinking 块：content 本身就是思考文本
			return msg.getText();
		}
		if (msg instanceof DeepSeekAssistantMessage dsm) {
			// DeepSeek：reasoning 在独立字段，content 是正文
			return dsm.getReasoningContent();
		}
		return null;
	}

	/**
	 * 抽取正文文本：非思考块的 {@code content}。思考块的 content 不算正文（避免与
	 * {@link #reasoningOf(AssistantMessage)} 重复）。
	 * @return 正文（可能为空串）；思考块返回 {@code null}
	 */
	public static @Nullable String textOf(AssistantMessage msg) {
		if (isReasoning(msg)) {
			// Anthropic thinking 块的 content 已作为 reasoning 返回，这里不再当正文
			return null;
		}
		return msg.getText();
	}

}
