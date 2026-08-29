package com.example.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 {@link EnableScheduling} 以驱动 {@code ArtifactCleanupJob}（按 cron 周期清理过期 artifact）。
 * 如未来不再需要任何定时任务，可移除该注解。
 */
@SpringBootApplication
@EnableScheduling
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
