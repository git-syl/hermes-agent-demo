package com.example.chat.config;

import com.example.chat.tools.ProxiedBraveWebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 注册 {@link ProxiedBraveWebSearchTool} 作为内置工具。
 *
 * <p>用本项目内的 {@link ProxiedBraveWebSearchTool} 替代上游
 * {@code org.springaicommunity.agent.tools.BraveWebSearchTool} —— 唯一目的：
 * 允许通过 {@code app.brave-search.base-url} 配置 API 端点，支持反代 / 镜像，
 * 解决国内直连 {@code api.search.brave.com} 超时的问题。工具签名（@Tool name=WebSearch、
 * 参数、返回格式）与上游完全一致，客户端无感知。
 *
 * <p>条件：{@code app.brave-search.api-key} 必须非空，否则不注册（启动不报错）。
 * 注册后由 {@code ChatService.filterBuiltinTools} 与 {@code BuiltinTools} 一起
 * 暴露给模型；客户端在 {@code req.tools} 中写入 {@code "WebSearch"} 即启用。
 */
@Configuration
@EnableConfigurationProperties(BraveSearchProperties.class)
@ConditionalOnProperty(prefix = "app.brave-search", name = "api-key")
public class BraveSearchConfig {

    private static final Logger log = LoggerFactory.getLogger(BraveSearchConfig.class);

    @Bean
    public ProxiedBraveWebSearchTool braveWebSearchTool(BraveSearchProperties props) {
        String apiKey = props.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "app.brave-search.api-key must not be blank when BraveSearchConfig is enabled");
        }
        ProxiedBraveWebSearchTool tool = ProxiedBraveWebSearchTool.builder(apiKey, props.getBaseUrl())
                .resultCount(props.getResultCount())
                .build();
        log.info("ProxiedBraveWebSearchTool registered (baseUrl={}, resultCount={}, apiKey=***{})",
                props.getBaseUrl(),
                props.getResultCount(),
                apiKey.length() > 4 ? apiKey.substring(apiKey.length() - 4) : "****");
        return tool;
    }
}
