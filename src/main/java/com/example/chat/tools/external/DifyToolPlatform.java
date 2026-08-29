package com.example.chat.tools.external;

import com.example.chat.api.dto.ChatRequest;
import com.example.chat.config.ExternalToolsProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ExternalToolPlatform} implementation for Dify {@code /v1/workflows/run} HTTP bridges.
 *
 * <p>Wire format matches Dify's <a href="https://docs.dify.ai/guides/workflow/publish">workflow run</a> API:
 * <pre>{@code
 * POST <runUrl>
 * Authorization: Bearer <token>
 * Content-Type: application/json
 *
 * {
 *   "inputs":        { ...LLM-generated arguments matching the tool inputSchema... },
 *   "user":          "<user-id>",
 *   "response_mode": "blocking"
 * }
 * }</pre>
 *
 * <p>Per-tool {@code runUrl} / {@code token} / {@code user} come from
 * {@link ChatRequest.ExternalTool#config()} so the caller can register one Dify app per
 * external tool. Platform-level {@code chat.external-tools.platforms.dify.*} (endpoint /
 * headers) acts as fallback for legacy callers that haven't migrated to per-tool config yet.
 *
 * <p>{@link ToolContext} fields (userId / apiKey / tenantId / ...) are flattened into
 * {@code X-Ctx-*} request headers; never written into the LLM-visible body.
 */
@Component
public class DifyToolPlatform implements ExternalToolPlatform {

    public static final String NAME = "dify";

    private static final Logger log = LoggerFactory.getLogger(DifyToolPlatform.class);

    private final ExternalToolsProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DifyToolPlatform(ExternalToolsProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.restClient = buildRestClient(props);
    }

    /**
     * 用 {@code chat.external-tools.platforms.dify.timeout-ms} 构造一个带超时的 RestClient。
     * <ul>
     *   <li>读超时（response timeout）= 配置值；Dify 工作流允许跑到 5 分钟，默认就是 5 分钟。</li>
     *   <li>连接超时固定 10 秒（建连阶段不该慢，慢就是网络不通，没必要等 5 分钟）。</li>
     *   <li>未配置 dify 平台或 timeoutMs <= 0 时，回落到 {@link #DEFAULT_READ_TIMEOUT_MS}。</li>
     * </ul>
     * 不在 {@code dispatch} 里 per-call 构造，是因为 {@link RestClient} 重用底层连接池能省 TCP/TLS 握手。
     */
    private static RestClient buildRestClient(ExternalToolsProperties props) {
        ExternalToolsProperties.PlatformProps cfg =
                props != null ? props.getPlatforms().get(NAME) : null;
        long readMs = cfg != null && cfg.getTimeoutMs() > 0 ? cfg.getTimeoutMs() : DEFAULT_READ_TIMEOUT_MS;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));
        factory.setReadTimeout(Duration.ofMillis(readMs));
        return RestClient.builder().requestFactory(factory).build();
    }

    /** 默认读超时：5 分钟。Dify 复杂工作流（多步 LLM + 检索）跑满这个时间是合理上限。 */
    private static final long DEFAULT_READ_TIMEOUT_MS = 300_000L;
    private static final long CONNECT_TIMEOUT_MS = 10_000L;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String dispatch(ExternalToolInvocation invocation) {
        String toolName = invocation.toolName();
        String arguments = invocation.arguments();

        ChatRequest.ExternalTool def = invocation.definition();
        Map<String, Object> toolConfig = def != null && def.config() != null ? def.config() : Map.of();

        ExternalToolsProperties.PlatformProps cfg = props.getPlatforms().get(NAME);

        String runUrl = firstNonBlank(asString(toolConfig.get("runUrl")),
                cfg != null ? cfg.getEndpoint() : null);
        String token = asString(toolConfig.get("token"));
        String user = asString(toolConfig.get("user"));

        // LLM-generated arguments (JSON object string) -> Dify "inputs" map.
        // config.toolContext 里的键值原样合并进 inputs，且优先级高于 LLM 参数：
        // 这些是服务端注入的上下文（userId / tenantId 等），LLM 不应能覆写。
        Map<String, Object> inputs = new LinkedHashMap<>(parseArgumentsToMap(arguments));
        Map<String, Object> contextInputs = asObjectMap(toolConfig.get("toolContext"));
        if (!contextInputs.isEmpty()) {
            inputs.putAll(contextInputs);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inputs", inputs);
        if (user != null && !user.isBlank()) {
            payload.put("user", user);
        }
        payload.put("response_mode", "blocking");

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "Failed to serialize Dify workflow payload: " + e.getMessage();
        }

        Map<String, String> ctxHeaders = extractContextHeaders(invocation.toolContext());

        log.info("[dify-tool] name={} runUrl={} user={} args={} ctxHeaders={} payload={}",
                toolName, runUrl, user, arguments, maskHeaders(ctxHeaders), body);

        if (runUrl == null || runUrl.isBlank()) {
            String stub = "{\"ok\":true,\"stub\":true,\"tool\":\"" + toolName
                    + "\",\"Mock假设dify工作流返回了：\":" + body + "}";
            log.info("[dify-tool] runUrl not configured (externalTools[].config.runUrl or"
                    + " chat.external-tools.platforms.dify.endpoint), returning stub for name={}", toolName);
            return stub;
        }

        log.info("[dify-tool] dispatching name={} -> {}", toolName, runUrl);
        try {
            String response = restClient.post()
                    .uri(runUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        // Platform-level static headers first (e.g. corp proxy auth).
                        if (cfg != null && cfg.getHeaders() != null) {
                            cfg.getHeaders().forEach(h::add);
                        }
                        // Per-tool token wins over any static Authorization header.
                        if (token != null && !token.isBlank()) {
                            h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                        }
                        ctxHeaders.forEach(h::add);
                    })
                    .body(body)
                    .retrieve()
                    .body(String.class);
            log.info("[dify-tool] response name={} body={}", toolName, response);
            return response != null ? response : "";
        } catch (RuntimeException e) {
            log.warn("[dify-tool] dispatch failed name={}: {}", toolName, e.getMessage());
            return "External tool '" + toolName + "' failed: " + e.getMessage();
        }
    }

    /**
     * 把 LLM 产出的 JSON 字符串参数解析为 Dify 期望的 {@code inputs} Map。
     * <ul>
     *   <li>{@code null} / 空串 → 空 Map（Dify 允许无 inputs）；</li>
     *   <li>解析失败 → 退化为 {@code {"_raw": <原文>}}，让 Dify 侧拿到尽量多信息排查，
     *       而不是直接 500。</li>
     * </ul>
     */
    private Map<String, Object> parseArgumentsToMap(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("[dify-tool] arguments not valid JSON object, forwarding as _raw: {}", e.getMessage());
            return Map.of("_raw", arguments);
        }
    }

    private static String asString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return null;
    }

    /**
     * Coerce {@code config.toolContext} 到一个 {@code Map<String,Object>}：
     * <ul>
     *   <li>{@code null} / 非 Map → 空 Map（容错，避免 ClassCastException）；</li>
     *   <li>key 为 null/空 → 跳过；value 原样保留（含嵌套对象 / 数组），由 Jackson 序列化时一起写出去。</li>
     * </ul>
     */
    private static Map<String, Object> asObjectMap(Object v) {
        if (!(v instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            Object k = e.getKey();
            if (k == null) {
                continue;
            }
            String key = k.toString();
            if (key.isBlank()) {
                continue;
            }
            out.put(key, e.getValue());
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    /**
     * 把 ToolContext 里的标量字段拍平成 HTTP header（{@code X-Ctx-*}）。
     * <ul>
     *   <li>value 为空串/null 跳过（与 ChatService 端的占位语义一致）；</li>
     *   <li>非字符串/数字/布尔的复杂对象直接跳过，避免 toString 出乱码。</li>
     * </ul>
     */
    private static Map<String, String> extractContextHeaders(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null || toolContext.getContext().isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : toolContext.getContext().entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k == null || k.isBlank() || v == null) {
                continue;
            }
            String s;
            if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean) {
                s = v.toString();
            } else {
                continue;
            }
            if (s.isBlank()) {
                continue;
            }
            String headerName = "X-Ctx-" + k.replaceAll("[^A-Za-z0-9_-]", "-");
            out.put(headerName, s);
        }
        return out;
    }

    /** 日志用：把可能含密钥的 header 值脱敏（首尾 2 字符 + ***）。 */
    private static Map<String, String> maskHeaders(Map<String, String> headers) {
        if (headers.isEmpty()) {
            return headers;
        }
        Map<String, String> masked = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            String lower = k.toLowerCase();
            if (lower.contains("key") || lower.contains("token") || lower.contains("secret")
                    || lower.contains("auth") || lower.contains("password")) {
                masked.put(k, v == null || v.length() <= 4
                        ? "***"
                        : v.substring(0, 2) + "***" + v.substring(v.length() - 2));
            } else {
                masked.put(k, v);
            }
        });
        return masked;
    }
}
