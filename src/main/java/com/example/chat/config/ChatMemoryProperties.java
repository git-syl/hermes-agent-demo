package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务端 ChatMemory 相关配置。绑定 {@code chat.memory.*}。
 *
 * <p>仅在 {@code ChatRequest.useServerMemory=true} 时生效（路径走
 * {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor}）；
 * 无状态调用走请求体 {@code history} 字段，不受这里影响。
 */
@ConfigurationProperties(prefix = "chat.memory")
public class ChatMemoryProperties {

    /**
     * LRU 版 ChatMemoryRepository 最多保留多少个活跃会话（sessionId）。超过后按
     * 最近最少使用顺序淘汰。粗略估算每会话内存上限：
     * {@code MessageWindowChatMemory.maxMessages × 单条 message 平均大小}。
     * 默认 1000 在普通文本对话下 ≈ 几十 MB；如果工具响应很大（bash 输出 / 文件内容）
     * 单会话可达数百 MB，请按需调小。
     */
    private int maxConversations = 1000;

    public int getMaxConversations() {
        return maxConversations;
    }

    public void setMaxConversations(int maxConversations) {
        this.maxConversations = maxConversations;
    }
}
