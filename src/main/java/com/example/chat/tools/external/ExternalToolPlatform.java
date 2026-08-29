package com.example.chat.tools.external;

/**
 * 外部工具平台 SPI。一个平台对应一类 wire format / 鉴权 / endpoint 约定，例如
 * Dify、n8n、Coze、FastGPT，或自研的 HTTP webhook。
 *
 * <p>实现以 Spring {@code @Component} 暴露即可，{@link ExternalToolPlatformRegistry}
 * 会自动按 {@link #name()} 收集；模型选中的 {@link com.example.chat.api.dto.ChatRequest.ExternalTool}
 * 经路由后由对应实现执行。
 *
 * <p>注意 {@link #name()} 与"LLM 模型 provider"是两个独立概念，前者描述工具来源平台，
 * 后者描述模型厂商（{@code ANTHROPIC} / {@code DEEPSEEK} / ...），不要混用。
 */
public interface ExternalToolPlatform {

    /**
     * 平台唯一标识，与 {@link com.example.chat.api.dto.ChatRequest.ExternalTool#platform()}
     * 对应。建议小写短词（{@code "dify"} / {@code "n8n"} / {@code "coze"}）；
     * 注册时统一按小写匹配。
     */
    String name();

    /**
     * 执行一次工具调用。返回值会作为 tool result 回填给模型。
     *
     * <p>失败应返回可被模型理解的错误字符串，而非抛出异常——避免单个外部工具失败
     * 中断整轮对话；只有不可恢复的配置/编程错误才允许抛出 {@link RuntimeException}。
     */
    String dispatch(ExternalToolInvocation invocation);
}
