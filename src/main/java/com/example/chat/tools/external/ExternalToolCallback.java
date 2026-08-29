package com.example.chat.tools.external;

import com.example.chat.api.dto.ChatRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@link ToolCallback} adapter for an externally-defined tool. The schema /
 * description / name come from the chat request; execution is delegated to the
 * {@link ExternalToolPlatform} resolved from {@link ChatRequest.ExternalTool#platform()}.
 *
 * <p>From the model's perspective this looks like any other tool — it picks one
 * based on description + JSON schema. The platform (Dify, n8n, ...) is fully
 * opaque to the LLM.
 */
public class ExternalToolCallback implements ToolCallback {

    private final ChatRequest.ExternalTool source;
    private final ExternalToolPlatform platform;
    private final ToolDefinition definition;

    public ExternalToolCallback(ChatRequest.ExternalTool source, ExternalToolPlatform platform) {
        this.source = source;
        this.platform = platform;
        this.definition = DefaultToolDefinition.builder()
                .name(source.name())
                .description(source.description() != null ? source.description() : "")
                .inputSchema(source.inputSchema() != null ? source.inputSchema() : "{\"type\":\"object\"}")
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return platform.dispatch(new ExternalToolInvocation(source, source.name(), toolInput, null));
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        // ToolContext 透传给 platform；由 platform 决定哪些字段往下游 header / 私有
        // 字段里塞，禁止写入 LLM 可见的 body。
        return platform.dispatch(new ExternalToolInvocation(source, source.name(), toolInput, toolContext));
    }
}
