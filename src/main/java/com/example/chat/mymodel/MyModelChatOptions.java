package com.example.chat.mymodel;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * 自定义模型的 ChatOptions，扩展 Spring AI 的 {@link DefaultToolCallingChatOptions}
 * 以继承标准工具调用能力（{@code toolCallbacks / toolContext}），
 * 并新增本接口私有字段：{@code thinking / userId / assistantId / jobType}。
 *
 * <p>Spring AI 2.0.0-RC1 起 {@link ChatOptions} 体系强制不可变：所有字段为 {@code final}，
 * 唯一构造路径是 builder。本类同样按不可变模型实现——所有自定义字段也是 {@code final}，
 * 通过 {@link Builder#build()} 一次性塞入。
 *
 * <p>RC1 已从 {@link ToolCallingChatOptions} 中删除 {@code toolNames} 与
 * {@code internalToolExecutionEnabled}：工具列表统一通过 {@code toolCallbacks} 显式提供，
 * 工具执行循环交给 {@code ToolCallingAdvisor} 驱动。
 *
 * <p>{@code assistantId} 仅用于透传给自定义模型服务做调用统计，模型推理本身不消费。
 */
public class MyModelChatOptions extends DefaultToolCallingChatOptions {

    private final @Nullable String thinking;
    private final @Nullable Long userId;
    private final @Nullable Long assistantId;
    private final @Nullable Integer jobType;

    protected MyModelChatOptions(
            @Nullable List<ToolCallback> toolCallbacks,
            @Nullable Map<String, Object> toolContext,
            @Nullable String model,
            @Nullable Double frequencyPenalty,
            @Nullable Integer maxTokens,
            @Nullable Double presencePenalty,
            @Nullable List<String> stopSequences,
            @Nullable Double temperature,
            @Nullable Integer topK,
            @Nullable Double topP,
            @Nullable String thinking,
            @Nullable Long userId,
            @Nullable Long assistantId,
            @Nullable Integer jobType) {
        super(toolCallbacks, toolContext, model, frequencyPenalty, maxTokens,
                presencePenalty, stopSequences, temperature, topK, topP);
        this.thinking = thinking;
        this.userId = userId;
        this.assistantId = assistantId;
        this.jobType = jobType;
    }

    public @Nullable String getThinking()    { return this.thinking; }
    public @Nullable Long getUserId()        { return this.userId; }
    public @Nullable Long getAssistantId()   { return this.assistantId; }
    public @Nullable Integer getJobType()    { return this.jobType; }

    public static Builder builder() { return new Builder(); }

    /**
     * 覆盖父类 {@code mutate()}：返回 {@link Builder}，确保 Spring AI 在合并 options
     * 时保留本类型（否则会被降级为 {@link DefaultToolCallingChatOptions}，
     * 导致 {@code thinking / userId / jobType} 字段丢失）。
     */
    @Override
    public Builder mutate() {
        return new Builder()
                .model(getModel())
                .frequencyPenalty(getFrequencyPenalty())
                .maxTokens(getMaxTokens())
                .presencePenalty(getPresencePenalty())
                .stopSequences(getStopSequences())
                .temperature(getTemperature())
                .topK(getTopK())
                .topP(getTopP())
                .toolCallbacks(getToolCallbacks())
                .toolContext(getToolContext())
                .thinking(this.thinking)
                .userId(this.userId)
                .assistantId(this.assistantId)
                .jobType(this.jobType);
    }

    /**
     * Builder 继承 {@link DefaultToolCallingChatOptions.Builder} 以复用全部标准字段
     * （model / temperature / toolCallbacks / toolContext / ...），并新增
     * {@code thinking / userId / assistantId / jobType}。
     */
    public static class Builder extends DefaultToolCallingChatOptions.Builder<Builder> {

        private @Nullable String thinking;
        private @Nullable Long userId;
        private @Nullable Long assistantId;
        private @Nullable Integer jobType;

        public Builder thinking(@Nullable String thinking)       { this.thinking = thinking;       return self(); }
        public Builder userId(@Nullable Long userId)             { this.userId = userId;           return self(); }
        public Builder assistantId(@Nullable Long assistantId)   { this.assistantId = assistantId; return self(); }
        public Builder jobType(@Nullable Integer jobType)        { this.jobType = jobType;         return self(); }

        @Override
        public Builder combineWith(ChatOptions.Builder<?> other) {
            super.combineWith(other);
            if (other instanceof Builder that) {
                if (that.thinking != null)    this.thinking = that.thinking;
                if (that.userId != null)      this.userId = that.userId;
                if (that.assistantId != null) this.assistantId = that.assistantId;
                if (that.jobType != null)     this.jobType = that.jobType;
            }
            return self();
        }

        @Override
        public MyModelChatOptions build() {
            return new MyModelChatOptions(
                    this.toolCallbacks, this.toolContext,
                    this.model, this.frequencyPenalty, this.maxTokens,
                    this.presencePenalty, this.stopSequences, this.temperature,
                    this.topK, this.topP,
                    this.thinking, this.userId, this.assistantId, this.jobType);
        }
    }
}
