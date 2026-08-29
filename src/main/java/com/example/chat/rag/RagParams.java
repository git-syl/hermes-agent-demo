package com.example.chat.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.Map;

/** RAG 接口入参小工具。 */
final class RagParams {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private RagParams() {
    }

    /**
     * 解析接口传入的 {@code metadata} JSON 字符串（如 {@code {"category":"redis","lang":"zh"}}）为 Map。
     * 为空返回空 Map；格式非法直接 fail-fast，避免静默丢掉用户意图。
     */
    static Map<String, Object> parseMetadata(ObjectMapper objectMapper, String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        }
        catch (Exception e) {
            throw new IllegalArgumentException("metadata 不是合法的 JSON 对象：" + json, e);
        }
    }
}
