package com.example.chat.service;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReasoningExtractor} 的纯函数回归：覆盖各厂商"思考 vs 正文"分类的所有分支。
 * <p>主 agent（{@code ChatService.toEvents} / {@code extractReasoningAndText}）与子代理
 * （{@code SandboxSubagentExecutor}）共用本类，分类正确性在此单点保证。
 */
class ReasoningExtractorTest {

	@Test
	void plainAssistantMessageIsText() {
		// 普通消息：content 是正文，无 thinking metadata，非 DeepSeek
		AssistantMessage msg = new AssistantMessage("hello world");

		assertThat(ReasoningExtractor.isReasoning(msg)).isFalse();
		assertThat(ReasoningExtractor.reasoningOf(msg)).isNull();
		assertThat(ReasoningExtractor.textOf(msg)).isEqualTo("hello world");
	}

	@Test
	void anthropicThinkingMetadataIsReasoning() {
		// Anthropic 流式 thinking 块：metadata.thinking == TRUE，content 即思考文本
		AssistantMessage msg = AssistantMessage.builder()
			.content("Let me think...")
			.properties(Map.of("thinking", Boolean.TRUE))
			.build();

		assertThat(ReasoningExtractor.isReasoning(msg)).isTrue();
		assertThat(ReasoningExtractor.reasoningOf(msg)).isEqualTo("Let me think...");
		// 思考块的 content 不再当正文，避免重复
		assertThat(ReasoningExtractor.textOf(msg)).isNull();
	}

	@Test
	void anthropicSignatureMetadataIsReasoning() {
		// Anthropic 非流式 thinking 块：metadata.signature 存在
		AssistantMessage msg = AssistantMessage.builder()
			.content("thinking block")
			.properties(Map.of("signature", "sig-123"))
			.build();

		assertThat(ReasoningExtractor.isReasoning(msg)).isTrue();
		assertThat(ReasoningExtractor.reasoningOf(msg)).isEqualTo("thinking block");
		assertThat(ReasoningExtractor.textOf(msg)).isNull();
	}

	@Test
	void deepSeekReasoningContentRoutedToReasoning() {
		// DeepSeek：reasoning 在独立字段 reasoning_content，content 永远是正文
		DeepSeekAssistantMessage msg = DeepSeekAssistantMessage.builder()
			.content("final answer")
			.reasoningContent("step by step")
			.build();

		// DeepSeek 的 content 永远是正文，isReasoning 恒 false（reasoning 不在 content 里）
		assertThat(ReasoningExtractor.isReasoning(msg)).isFalse();
		assertThat(ReasoningExtractor.reasoningOf(msg)).isEqualTo("step by step");
		assertThat(ReasoningExtractor.textOf(msg)).isEqualTo("final answer");
	}

	@Test
	void deepSeekWithoutReasoningContent() {
		// DeepSeek-chat（非思考模型）：reasoning_content 为 null，只有正文
		DeepSeekAssistantMessage msg = DeepSeekAssistantMessage.builder()
			.content("plain answer")
			.reasoningContent(null)
			.build();

		assertThat(ReasoningExtractor.isReasoning(msg)).isFalse();
		assertThat(ReasoningExtractor.reasoningOf(msg)).isNull();
		assertThat(ReasoningExtractor.textOf(msg)).isEqualTo("plain answer");
	}

	@Test
	void nullMetadataIsText() {
		// 无 metadata 的消息：当作正文，不误判为思考
		AssistantMessage msg = new AssistantMessage("no metadata here");

		assertThat(ReasoningExtractor.isReasoning(msg)).isFalse();
		assertThat(ReasoningExtractor.reasoningOf(msg)).isNull();
		assertThat(ReasoningExtractor.textOf(msg)).isEqualTo("no metadata here");
	}

}
