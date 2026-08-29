package com.example.chat.config;

import com.example.chat.service.LruInMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatMemoryProperties.class)
public class ChatClientConfig {

    /**
     * LRU 版内存 ChatMemoryRepository，替换 Spring AI 默认的无上限 {@code InMemoryChatMemoryRepository}
     * （裸 ConcurrentHashMap 会随活跃会话无界增长）。会话数上限 {@code chat.memory.max-conversations}，
     * 超出按 LRU 淘汰；生产建议改用 Redis / JDBC 版仓库。
     */
    @Bean
    public ChatMemoryRepository chatMemoryRepository(ChatMemoryProperties props) {
        return new LruInMemoryChatMemoryRepository(props.getMaxConversations());
    }

    /**
     * 服务端 ChatMemory，仅在 {@code ChatRequest.useServerMemory=true} 时被
     * {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor} 装载使用。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repo) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repo)
                .maxMessages(10)
                .build();
    }
}
