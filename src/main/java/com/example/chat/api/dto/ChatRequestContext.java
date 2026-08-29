package com.example.chat.api.dto;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一次 {@code /chat} 或 {@code /chat/stream} 请求的不可变上下文。把请求体、自定义 header、
 * 客户端地理信息、兜底后的 sessionId 打包，让下游方法签名瘦下来。
 *
 * <p>所有提取规则集中在 {@link #of(ChatRequest, HttpHeaders)} 静态工厂里，controller 只需要：
 * <pre>{@code chatService.streamChat(ChatRequestContext.of(request, headers));}</pre>
 *
 * <h2>headerCtx 由来</h2>
 * 请求头中以 {@value #CTX_HEADER_PREFIX} 开头的条目会被剥掉前缀后注入到工具
 * {@code ToolContext}（以及 skill 沙箱的环境变量）。例如
 * {@code X-Ctx-Authorization: Bearer xxx} → {@code Authorization=Bearer xxx}。
 * 大小写不敏感（HTTP/2 强制小写、{@link HttpHeaders} 也用 case-insensitive map）。
 *
 * <p>关于重复 key：HTTP 协议允许同名 header 出现多次，{@link HttpHeaders} 底层是
 * {@code MultiValueMap}，多个值会原样保留。这里按"逗号拼接所有值"的方式折叠，
 * 与 RFC 9110 §5.3 list 字段语义一致；如果业务希望取首/取尾或拒绝重复，
 * 在 {@link #joinValues} 处调整。
 *
 * <h2>client 字段由来</h2>
 * Cloudflare 注入的 {@code CF-IPCountry / CF-IPCity / CF-Region / CF-Timezone}
 * 会被解析后塞进 {@link ClientInfo}，由 {@code SystemPromptComposer} 决定是否渲染。
 * Cloudflare 用 {@code XX} / {@code T1} 表示"无法确定国家"，按缺失处理。
 *
 * <h2>sessionId 兜底</h2>
 * {@code req.sessionId()} 为空时用 UUID 兜底，写在 {@link #sessionId} 字段里供下游
 * （{@code ChatMemory}、{@code SkillCacheService}、返回响应体）使用；
 * 但 {@link com.example.chat.sandbox.SandboxSessionManager.SessionKey} 那一路仍读取
 * {@code req.sessionId()} 原值——让匿名/无 sessionId 请求自动走 EphemeralLease 不入缓存。
 */
public record ChatRequestContext(
        ChatRequest req,
        Map<String, String> headerCtx,
        ClientInfo client,
        String sessionId) {

    /** 自定义透传 header 的统一前缀，剥前缀后写入 ToolContext / 沙箱 env。 */
    public static final String CTX_HEADER_PREFIX = "X-Ctx-";

    /** Cloudflare 表示"无法确定国家"的占位值：{@code XX} 是未知，{@code T1} 是 Tor。 */
    private static final Set<String> CF_COUNTRY_UNKNOWN = Set.of("XX", "T1");

    /**
     * 从 HTTP 请求体 + 请求头组装一个上下文对象。{@code headers} 允许为 null（便于单测）。
     * 默认实现把 X-Ctx-* 与 CF-* 两套约定一次解析完，调用方拿到的就是干净结构化数据。
     */
    public static ChatRequestContext of(ChatRequest req, @Nullable HttpHeaders headers) {
        String sid = (req != null && StringUtils.hasText(req.sessionId()))
                ? req.sessionId()
                : UUID.randomUUID().toString();
        return new ChatRequestContext(req, extractCtxHeaders(headers), extractClientInfo(headers), sid);
    }

    /**
     * 显式使用 {@link HttpHeaders} 而非 {@code Map<String, String>}：后者底层会调用
     * {@code HttpServletRequest.getHeader(name)}，按 Servlet 规范只返回第一个值，
     * 重复 header 的其它值会被静默丢弃。
     */
    private static Map<String, String> extractCtxHeaders(@Nullable HttpHeaders headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        int prefixLen = CTX_HEADER_PREFIX.length();
        headers.forEach((name, values) -> {
            if (name == null || values == null || values.isEmpty()) {
                return;
            }
            if (!name.regionMatches(true, 0, CTX_HEADER_PREFIX, 0, prefixLen)) {
                return;
            }
            String key = name.substring(prefixLen);
            if (key.isBlank()) {
                return;
            }
            out.put(key, joinValues(values));
        });
        return out;
    }

    /**
     * 当前只关心国家/城市/地区/时区，IP、CF-Ray、scheme 等不会泄露给模型。
     * 任何字段缺失 / 为空 / 为 CF "未知占位"时按 {@code null} 处理。
     */
    private static ClientInfo extractClientInfo(@Nullable HttpHeaders headers) {
        if (headers == null) {
            return ClientInfo.EMPTY;
        }
        String country = normalize(headers.getFirst("CF-IPCountry"));
        if (country != null && CF_COUNTRY_UNKNOWN.contains(country.toUpperCase())) {
            country = null;
        }
        String city = urlDecode(normalize(headers.getFirst("CF-IPCity")));
        String region = normalize(headers.getFirst("CF-Region"));
        String timezone = normalize(headers.getFirst("CF-Timezone"));
        return new ClientInfo(country, city, region, timezone);
    }

    private static @Nullable String normalize(@Nullable String v) {
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /** CF-IPCity 等字段可能带 URL 编码（含空格、非 ASCII），解码失败时原样保留。 */
    private static @Nullable String urlDecode(@Nullable String v) {
        if (v == null) {
            return null;
        }
        try {
            return URLDecoder.decode(v, StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) {
            return v;
        }
    }

    private static String joinValues(List<String> values) {
        if (values.size() == 1) {
            String v = values.get(0);
            return v != null ? v : "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = values.get(i);
            if (v != null) {
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
