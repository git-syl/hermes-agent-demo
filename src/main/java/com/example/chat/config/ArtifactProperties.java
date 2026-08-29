package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where {@code ExportArtifact} stages files copied out of the sandbox, plus the
 * public URL prefix used to build download links returned to the LLM/front-end.
 *
 * <p>Bound to {@code chat.artifacts.*}.
 */
@ConfigurationProperties(prefix = "chat.artifacts")
public class ArtifactProperties {

    /** Local staging directory. One sub-folder per artifact id. */
    private String dir = System.getProperty("user.home") + "/.skills-demo/artifacts";

    /** Hard upper bound on per-file export size. Default 50 MiB. */
    private long maxSizeBytes = 50L * 1024 * 1024;

    /**
     * Public base URL the {@code DownloadController} is reachable at.
     * Used to build the URL handed back to the LLM. Override per-env if behind a reverse proxy.
     */
    private String baseUrl = "http://localhost:8080";

    /**
     * Artifact 子目录的保留天数，超过则被 {@code ArtifactCleanupJob} 清理。
     * {@code <= 0} 时禁用清理（即便 cron 触发也直接返回）。
     */
    private int ttlDays = 10;

    /**
     * 清理任务的 cron 表达式（Spring 6 标准 6 段 cron：秒 分 时 日 月 周）。
     * 默认每天凌晨 3 点。空串时禁用清理。
     */
    private String cleanupCron = "0 0 3 * * *";

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getTtlDays() {
        return ttlDays;
    }

    public void setTtlDays(int ttlDays) {
        this.ttlDays = ttlDays;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }
}
