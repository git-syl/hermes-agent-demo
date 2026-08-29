package com.example.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link SmartWebFetchTool} 作为内置工具。
 *
 * <p>工具内部固定绑定一个走 DeepSeek 的摘要 {@link ChatClient}，
 * 不跟随 {@code /chat/stream} 入参的 {@code modelName} 切换 ——
 * 该工具职责是"网页摘要"，固定走便宜+长上下文的 {@code deepseek-v4-flash} 性价比最高，
 * 也避免按请求 new 实例时丢掉缓存。
 *
 * <p>条件：仅 {@code app.smart-web-fetch.enabled=true}（默认 true）。
 * {@link DeepSeekChatModel} 通过 {@code spring-ai-starter-model-deepseek}
 * 自动注册，依赖直接走构造参数注入——若该 starter 被移除则启动报错（明确而不是静默失效）。
 *
 * <p>历史注释：曾尝试 {@code @ConditionalOnBean(DeepSeekChatModel.class)}，但
 * Spring Boot 文档明确警告：用户级 {@code @Configuration} 上的
 * {@code @ConditionalOnBean} 对自动配置 bean 不可靠（用户配置先于自动配置评估，
 * 此时 bean 尚未注册），会导致 {@code SmartWebFetchTool} 静默不注册、
 * 客户端 {@code req.tools=["WebFetch"]} 时模型反馈"没有这个工具"。
 *
 * <p>客户端启用：{@code ChatRequest.tools} 写入 {@code "WebFetch"}
 * （与 {@code @Tool(name)} 一致），与 {@code BuiltinTools} / {@code BraveWebSearchTool}
 * 一起被 {@code ChatService.filterBuiltinTools} 扫描出来。
 */
@Configuration
@EnableConfigurationProperties(SmartWebFetchProperties.class)
@ConditionalOnProperty(prefix = "app.smart-web-fetch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SmartWebFetchConfig {

    private static final Logger log = LoggerFactory.getLogger(SmartWebFetchConfig.class);

    /**
     * 摘要专用 ChatClient：绑定 deepseek，model 显式指定，避免被全局
     * {@code spring.ai.deepseek.chat.options.model} 误改影响摘要任务。
     * 不参与 {@code ModelRouter} 路由。
     *
     * <p>注意：{@code ChatClient.Builder.defaultOptions(ChatOptions.Builder)} 接收的是
     * <b>options 的 builder</b>（内部按需克隆），不是 {@code build()} 后的实例。
     * 这里直接把 {@code DeepSeekChatOptions.Builder} 传进去即可。
     */
    @Bean("smartWebFetchSummaryChatClient")
    public ChatClient smartWebFetchSummaryChatClient(DeepSeekChatModel deepSeekChatModel,
                                                     SmartWebFetchProperties props) {
        log.info("SmartWebFetch summary ChatClient bound to DeepSeek model={}", props.getModel());
        return ChatClient.builder(deepSeekChatModel)
                .defaultOptions(DeepSeekChatOptions.builder().model(props.getModel()))
                .build();
    }

    /**
     * SmartWebFetchTool 实现 {@link AutoCloseable}，Spring 在容器关闭时会自动调用
     * {@code close()} 释放 HttpClient 与缓存，无需手动管理。
     */
    @Bean(destroyMethod = "close")
    public SmartWebFetchTool smartWebFetchTool(
            @org.springframework.beans.factory.annotation.Qualifier("smartWebFetchSummaryChatClient")
            ChatClient summaryChatClient,
            SmartWebFetchProperties props) {
        SmartWebFetchTool tool = SmartWebFetchTool.builder(summaryChatClient)
                .maxContentLength(props.getMaxContentLength())
                .maxCacheSize(props.getMaxCacheSize())
                .domainSafetyCheck(props.isDomainSafetyCheck())
                .maxRetries(props.getMaxRetries())
                .build();
        log.info("SmartWebFetchTool registered (maxContentLength={}, maxCacheSize={}, domainSafetyCheck={}, maxRetries={})",
                props.getMaxContentLength(), props.getMaxCacheSize(), props.isDomainSafetyCheck(), props.getMaxRetries());
        return tool;
    }
}
