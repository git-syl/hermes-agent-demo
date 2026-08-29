package com.example.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Streaming + {@link MessageChatMemoryAdvisor} + tool calls writes a malformed AssistantMessage
 * (text content AND orphan {@code tool_calls}) into chat memory. Replaying it on the next turn
 * makes DeepSeek (and any OpenAI-compatible API) return HTTP 400:
 * <code>"An assistant message with 'tool_calls' must be followed by tool messages
 * responding to each 'tool_call_id'."</code>
 *
 * <p>Root cause: {@code MessageAggregator#aggregate} folds every chunk of the streaming flux
 * (intermediate tool-call chunk + final text chunk) into a single AssistantMessage.
 *
 * <p><b>Status on Spring AI 2.0 GA:</b> this bug is permanently fixed upstream by removing the
 * offending {@code streamToolCallResponses} option from {@code ToolCallingAdvisor.Builder} —
 * intermediate tool-call chunks are no longer forwarded downstream, so memory advisors cannot
 * see orphan {@code tool_calls}. The test is kept as a regression guard against future
 * accidental reintroduction (e.g. a custom advisor that re-emits tool-call chunks).
 *
 * <p>Run with: {@code DEEPSEEK_API_KEY=sk-... mvn -Dtest=DeepSeekChatMemoryToolCallReproTest test}
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekChatMemoryToolCallReproTest {

    static class Clock {
        @Tool(name = "getDateTime", description = "Get the current local date and time as an ISO-8601 string.")
        String getDateTime() {
            return LocalDateTime.now().toString();
        }
    }

    @Test
    void streaming_with_serverMemory_and_toolCalls_breaks_secondTurn() {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();

        ChatClient client = ChatClient.builder(DeepSeekChatModel.builder()
                        .deepSeekApi(DeepSeekApi.builder().apiKey(System.getenv("DEEPSEEK_API_KEY")==null?"sk-*******":null).build())
                        .options(DeepSeekChatOptions.builder().model("deepseek-v4-flash").build())
                        .build())
                .defaultAdvisors(
                        // GA 已删除 streamToolCallResponses(true)：即使想重现旧 bug 也无法再通过该 builder 触发。
                        // 保留 ToolCallingAdvisor 显式声明，确保自动注册的默认 advisor 不会被绕开。
                        ToolCallingAdvisor.builder().build(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(new Clock())
                .build();

        // Round 1: triggers a tool call, then a final text reply.
        // doOnNext prints each ChatResponse chunk that ToolCallingAdvisor emits downstream,
        // so we can see exactly which chunks carry tool_calls and which carry final text.
        System.out.println("===== Round 1 downstream chunks =====");
        client.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv1"))
                .user("What time is it?")
                .stream().chatResponse()
                .doOnNext(r -> {
                    var gen = r.getResult();
                    var msg = gen != null ? gen.getOutput() : null;
                    var meta = gen != null ? gen.getMetadata() : null;
                    System.out.println(" - finishReason=" + (meta != null ? meta.getFinishReason() : null)
                            + " toolCalls=" + (msg != null ? msg.getToolCalls() : null)
                            + " content=" + (msg != null ? msg.getText() : null));
                })
                .blockLast();

        // Forensic: the final assistant message in memory carries BOTH content AND orphan
        // tool_calls, with no tool-response messages between them. This is the bug.
        System.out.println("===== Memory after round 1 =====");
        chatMemory.get("conv1").forEach(m -> System.out.println(" - " + m.getMessageType() + ": " + m));

        // Round 2: a plain "thanks" that needs no tool. History is replayed and DeepSeek rejects.
        WebClientResponseException error = (WebClientResponseException) assertThatThrownBy(() ->
                client.prompt()
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "conv1"))
                        .user("Thanks")
                        .stream().chatResponse().blockLast())
                .isInstanceOf(WebClientResponseException.BadRequest.class)
                .actual();

        System.out.println("===== Round 2 upstream body =====");
        System.out.println(error.getResponseBodyAsString());

        assertThat(error.getResponseBodyAsString())
                .contains("'tool_calls' must be followed by tool messages");
    }
}
