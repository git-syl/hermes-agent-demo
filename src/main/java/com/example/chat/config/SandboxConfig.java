package com.example.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Replaces the old {@code AgentToolsConfig}. The previous design exposed
 * {@code ShellTools}/{@code FileSystemTools} singletons that ran with full host
 * privileges — that TODO is now resolved by per-request sandboxes built by
 * {@link com.example.chat.sandbox.SandboxFactory}, which routes all tool
 * execution through {@link io.github.markpollack.sandbox.Sandbox} (Docker by
 * default).
 *
 * <p>Only purpose now: pull {@link SandboxProperties} into the Spring toolContext.
 */
@Configuration
@EnableConfigurationProperties({SandboxProperties.class, ArtifactProperties.class, ChatPromptProperties.class,
        ExternalToolsProperties.class, ToolPolicyProperties.class})
public class SandboxConfig {
}
