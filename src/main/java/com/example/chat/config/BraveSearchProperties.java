package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Brave Web Search 配置。
 *
 * <p>仅当 {@code app.brave-search.api-key} 非空时
 * {@link BraveSearchConfig} 才会注册 {@code BraveWebSearchTool} bean，
 * 进而被 {@code ChatService} 加入"内置工具池"，按工具名 {@code WebSearch}
 * 与其它 {@code @Tool} 一同走 {@code req.tools} 白名单。
 */
@ConfigurationProperties(prefix = "app.brave-search")
public class BraveSearchProperties {

    /** Brave Search API subscription token；为空时不注册 web search 工具。 */
    private String apiKey;

    /**
     * API base URL。默认指向 Brave 官方 {@code https://api.search.brave.com}；
     * 在国内或受限网络中，改成你自建的反向代理 / 镜像地址即可，路径和鉴权头协议保持兼容
     * （即同样向 {@code /res/v1/web/search} 转发，并允许 {@code X-Subscription-Token} 头透传）。
     */
    private String baseUrl = "https://api.search.brave.com";

    /** 单次搜索返回结果数；Brave API 上限约 20。 */
    private int resultCount = 10;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getResultCount() {
        return resultCount;
    }

    public void setResultCount(int resultCount) {
        this.resultCount = resultCount;
    }
}
