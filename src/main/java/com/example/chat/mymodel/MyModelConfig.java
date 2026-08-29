package com.example.chat.mymodel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 自定义模型的 Bean 装配。仅当 {@code my-model.enabled=true} 时启用。
 */
@Configuration
@ConditionalOnProperty(name = "my-model.enabled", havingValue = "true")
@EnableConfigurationProperties(MyModelProperties.class)
public class MyModelConfig {

    @Bean
    public MyModelApi myModelApi(MyModelProperties properties,
                                 RestClient.Builder restClientBuilder,
                                 WebClient.Builder webClientBuilder) {
        return new MyModelApi(properties.getBaseUrl(), restClientBuilder, webClientBuilder);
    }

    @Bean
    public MyModelChatModel myModelChatModel(MyModelApi api, MyModelProperties properties) {
        return new MyModelChatModel(api, properties);
    }
}
