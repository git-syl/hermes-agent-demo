package com.example.chat.tools.external;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按 {@link ExternalToolPlatform#name()} 收集容器中所有平台实现，
 * 供 {@code ChatService} 按请求中的 {@code platform} 字段路由。
 *
 * <p>未指定 {@code platform} 的请求（{@code null} / 空串）回退到
 * {@link #DEFAULT_PLATFORM}，以兼容老格式（{@code externalTools[]} 不含 platform 字段）。
 *
 * <p>未注册的 {@code platform} 抛 {@link IllegalArgumentException} —— 由 controller
 * 层把它包成 4xx，提示调用方拼写错误，避免静默退化到错误平台。
 */
@Component
public class ExternalToolPlatformRegistry {

    /** 兼容老请求：未填 {@code platform} 时按此名称回退。 */
    public static final String DEFAULT_PLATFORM = "dify";

    private final Map<String, ExternalToolPlatform> byName;

    public ExternalToolPlatformRegistry(List<ExternalToolPlatform> platforms) {
        this.byName = platforms.stream()
                .collect(Collectors.toUnmodifiableMap(
                        p -> p.name().toLowerCase(Locale.ROOT),
                        Function.identity()));
    }

    /**
     * 按 platform 名解析对应实现。匹配统一小写，未传则回退到 {@link #DEFAULT_PLATFORM}。
     *
     * @throws IllegalArgumentException 当对应平台未注册时
     */
    public ExternalToolPlatform resolve(@Nullable String platform) {
        String key = (platform == null || platform.isBlank())
                ? DEFAULT_PLATFORM
                : platform.toLowerCase(Locale.ROOT);
        ExternalToolPlatform impl = byName.get(key);
        if (impl == null) {
            throw new IllegalArgumentException(
                    "Unknown external tool platform: '" + key
                            + "' (available: " + byName.keySet() + ")");
        }
        return impl;
    }
}
