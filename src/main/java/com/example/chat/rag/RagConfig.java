package com.example.chat.rag;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 RAG 模块的配置属性与 Bean。向量库、embedding 的 bean 由 Spring AI 自动装配，
 * 这里把 {@link RagProperties} 纳入容器，并按第三方 embedding 限额定制批处理策略。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    /**
     * 覆盖 Spring AI 默认的 {@code TokenCountBatchingStrategy}（只按 token 总和分批、不限条数）。
     * 入库 {@code vectorStore.add(...)} 会用它把文档按"条数 + token"双重上限分批，避免超第三方 embedding 限额。
     * 自动配置侧是 {@code @ConditionalOnMissingBean}，提供本 Bean 即生效。
     */
    @Bean
    public BatchingStrategy batchingStrategy(RagProperties props) {
        return new BoundedBatchingStrategy(
                props.getEmbedding().getBatchSize(),
                props.getEmbedding().getMaxInputTokens());
    }
}
