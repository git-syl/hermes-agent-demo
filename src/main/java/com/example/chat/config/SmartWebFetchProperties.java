package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SmartWebFetch (网页抓取 + AI 摘要) 工具配置。
 *
 * <p>该工具构造时绑定一个固定 {@code ChatClient}，不跟随请求级 {@code modelName} 切换 ——
 * 选择固定 deepseek-v4-flash 是因为它便宜、上下文足够（64K），适合处理大段 Markdown 网页。
 *
 * <p>{@code domain-safety-check} 默认关闭：它会向 claude.ai 域名查询安全性，
 * 在国内网络中往往超时阻塞每次抓取请求。
 */
@ConfigurationProperties(prefix = "app.smart-web-fetch")
public class SmartWebFetchProperties {

    /** 是否启用。false 时不注册 bean，等同于不暴露 WebFetch 工具。 */
    private boolean enabled = true;

    /** 摘要用模型名；DeepSeek provider 内的具体 model（默认 deepseek-v4-flash）。 */
    private String model = "deepseek-v4-flash";

    /** HTML→Markdown 后保留的最大字符数；超长部分截断。 */
    private int maxContentLength = 100_000;

    /** 进程内 LRU 缓存上限（按 url+prompt hash 缓存 15 分钟）。 */
    private int maxCacheSize = 100;

    /** 抓取前是否调用 claude.ai 域名安全检查；国内强烈建议保持 false。 */
    private boolean domainSafetyCheck = false;

    /** 抓取重试次数（5xx / 网络错误时指数退避）。 */
    private int maxRetries = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(int maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    public int getMaxCacheSize() {
        return maxCacheSize;
    }

    public void setMaxCacheSize(int maxCacheSize) {
        this.maxCacheSize = maxCacheSize;
    }

    public boolean isDomainSafetyCheck() {
        return domainSafetyCheck;
    }

    public void setDomainSafetyCheck(boolean domainSafetyCheck) {
        this.domainSafetyCheck = domainSafetyCheck;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
