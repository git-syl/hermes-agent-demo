package com.example.chat.api.dto;

import org.jspecify.annotations.Nullable;

/**
 * 来自 CDN / 反代（主要是 Cloudflare）的客户端地理信息，纯展示用。
 *
 * <p>所有字段都可空：上游头缺失、值为 Cloudflare 的"未知占位"（{@code XX} / {@code T1}）
 * 或纯空白时统一归一化为 {@code null}。{@code SystemPromptComposer} 注入到
 * {@code <context><client_info>} 段时按字段逐项判空跳过，全部为空时整段不渲染，
 * 避免把"空标签"塞给模型造成困惑。
 *
 * <p>字段对应关系：
 * <ul>
 *   <li>{@code country}：ISO-3166-1 alpha-2，来自 {@code CF-IPCountry}</li>
 *   <li>{@code city}：来自 {@code CF-IPCity}（CF Enterprise 才有），值是 URL-encoded，
 *       已由 controller 解码</li>
 *   <li>{@code region}：来自 {@code CF-Region}（省/州名）</li>
 *   <li>{@code timezone}：IANA 时区，来自 {@code CF-Timezone}</li>
 * </ul>
 */
public record ClientInfo(
        @Nullable String country,
        @Nullable String city,
        @Nullable String region,
        @Nullable String timezone) {

    public static final ClientInfo EMPTY = new ClientInfo(null, null, null, null);

    /** 任一字段非空才认为"有信息"；用于决定是否渲染 {@code <client_info>} 整段。 */
    public boolean isEmpty() {
        return isBlank(country) && isBlank(city) && isBlank(region) && isBlank(timezone);
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
