package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for external HTTP tool platforms (Dify / n8n / Coze / ...).
 * 多平台架构下，每个 {@code com.example.chat.tools.external.ExternalToolPlatform}
 * 实现按 {@code name()} 从 {@link #platforms} 取自己的 endpoint / headers / 超时。
 *
 * <p>endpoint 一律由服务端配置，禁止从请求里传，避免 SSRF。
 *
 * <p>绑定 {@code chat.external-tools.*}，示例：
 * <pre>{@code
 * chat:
 *   external-tools:
 *     platforms:
 *       dify:
 *         endpoint: https://api.dify.ai/v1/...
 *         timeout-ms: 30000
 *         headers:
 *           Authorization: Bearer xxx
 *       n8n:
 *         endpoint: https://n8n.example.com/webhook/xxx
 *         timeout-ms: 15000
 *         headers: {}
 * }</pre>
 */
@ConfigurationProperties(prefix = "chat.external-tools")
public class ExternalToolsProperties {

    /**
     * 各平台配置。key 与 {@code ExternalToolPlatform#name()} 对应（小写）。
     */
    private Map<String, PlatformProps> platforms = new LinkedHashMap<>();

    public Map<String, PlatformProps> getPlatforms() {
        return platforms;
    }

    public void setPlatforms(Map<String, PlatformProps> platforms) {
        this.platforms = platforms;
    }

    /** 单个平台的 HTTP 接入参数。 */
    public static class PlatformProps {

        /**
         * 平台接收 tool 调用的 HTTP 端点。空时对应平台进入 stub 模式：
         * 不发请求、直接回一个 echo 响应，便于本地联调。
         */
        private String endpoint;

        /**
         * 单次调用的读超时（毫秒），即等待对端响应的最长时间。建连超时在实现内固定 10s。
         * <p>默认 5 分钟：Dify / n8n 这类工作流平台的串接节点（多步 LLM + 检索 + 外部 API）
         * 跑满几分钟是常态，30s 默认会频繁误杀。需要更短/更长按需在 yaml 里覆盖。
         */
        private long timeoutMs = 300_000L;

        /** 每次调用都附带的静态 header（例如 Authorization）。 */
        private Map<String, String> headers = new LinkedHashMap<>();

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }
}
