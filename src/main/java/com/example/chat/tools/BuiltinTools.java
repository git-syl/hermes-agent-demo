package com.example.chat.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Built-in tools available to the chat. The set actually exposed to the model on each
 * request is filtered by name against {@code ChatRequest.tools}.
 */
@Component
public class BuiltinTools {

    private static final Logger log = LoggerFactory.getLogger(BuiltinTools.class);

    public static final String GET_DATE_TIME = "getDateTime";
    public static final String GET_CPU_COUNT = "getCpuCount";
    public static final String GET_PROFILE_INFO = "getGetProfileInfo";

    /** ToolContext keys —— ChatService 在 .toolContext(Map) 里以这些 key 注入。 */
    public static final String CTX_USER_ID = "userId";
    public static final String CTX_ASSISTANT_ID = "assistantId";
    public static final String CTX_SESSION_ID = "sessionId";
    public static final String CTX_API_KEY = "apiKey";

    @Tool(name = GET_DATE_TIME, description = "Get the current local date and time as an ISO-8601 string.")
    public String getDateTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Tool(name = GET_CPU_COUNT, description = "Get the number of CPU cores (logical processors) available to the JVM.")
    public int getCpuCount() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * {@code ToolContext} 参数不会进入暴露给模型的 JSON Schema，由 ChatService
     * 在每次请求时通过 {@code .toolContext(Map)} 注入 userId / apiKey 等上下文。
     */
    @Tool(name = GET_PROFILE_INFO, description = "Get profile information for a person by name extracted from the chat conversation.")
    public String getGetProfileInfo(
            @ToolParam(description = "The person's name to query, extracted from the user's chat message") String queryName,
            ToolContext toolContext) {
        if (queryName == null || queryName.isBlank()) {
            return "Please provide a name to query profile information.";
        }

        Object userId = toolContext != null ? toolContext.getContext().get(CTX_USER_ID) : null;
        Object apiKey = toolContext != null ? toolContext.getContext().get(CTX_API_KEY) : null;
        // 空字符串是 ChatService 端的「未提供」占位符（受 Spring AI 不允许 null value 的限制），
        // 这里统一归一化为 null，方便后续判空。
        if (userId != null && userId.toString().isBlank()) {
            userId = null;
        }
        if (apiKey != null && apiKey.toString().isBlank()) {
            apiKey = null;
        }
        // apiKey 仅用于权限/外部调用，绝不要回写到返回串里。
        boolean authorized = apiKey != null;

        // 调试用：确认 ToolContext 是否正确注入。apiKey 仅打印掩码，避免泄露。
        log.info("[tool:{}] queryName={}, ctxKeys={}, userId={}, apiKey={}, authorized={}",
                GET_PROFILE_INFO,
                queryName,
                toolContext != null ? toolContext.getContext().keySet() : "<null>",
                userId,
                mask(apiKey),
                authorized);

        String profile = switch (queryName.strip().toLowerCase()) {
            case "张三", "zhangsan", "zhang san" -> "name: 张三\nrole: Java Developer\ncity: Shanghai\nemail: zhangsan@example.com";
            case "李四", "lisi", "li si" -> "name: 李四\nrole: Product Manager\ncity: Beijing\nemail: lisi@example.com";
            case "王五", "wangwu", "wang wu" -> "name: 王五\nrole: Test Engineer\ncity: Shenzhen\nemail: wangwu@example.com";
            default -> null;
        };
        if (profile == null) {
            return "No profile information found for: " + queryName.strip();
        }
        return profile
                + "\nrequestedBy: " + (userId != null ? userId : "anonymous")
                + "\nauthorized: " + authorized;
    }

    /** 把敏感字符串脱敏后再写日志：保留首尾各 2 个字符，中间用 *** 代替。 */
    private static String mask(Object value) {
        if (value == null) {
            return "<null>";
        }
        String s = value.toString();
        if (s.isBlank()) {
            return "<blank>";
        }
        if (s.length() <= 4) {
            return "***";
        }
        return s.substring(0, 2) + "***" + s.substring(s.length() - 2);
    }
}
