package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内置系统提示词（人设/规则）。绑定 {@code chat.prompt.*}。
 *
 * <p>由 {@link SystemPromptComposer} 包进 XML 外壳后注入到 system 消息：
 * 服务方控制的硬规则放在 {@code <builtin_rules priority="highest">} 块，
 * 用户请求里携带的 system 字段放在 {@code <user_persona>} 块，冲突时以本段为准。
 */
@ConfigurationProperties(prefix = "chat.prompt")
public class ChatPromptProperties {

    /** 内置系统提示词，留空则 XML 外壳里 {@code <builtin_rules>} 段也会留空。 */
    private String system = "";

    /**
     * 注入到 {@code <context><current_time>} 的时区 ID（IANA），默认上海。
     * 容器宿主时区可能是 UTC，这里固定一下避免随部署环境漂移。
     */
    private String timezone = "Asia/Shanghai";

    public String getSystem() { return system; }
    public void setSystem(String system) { this.system = system; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
}
