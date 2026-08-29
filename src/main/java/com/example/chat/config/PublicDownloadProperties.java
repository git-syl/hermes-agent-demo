package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 公开资料（skills zip、agent md 等）对外下载的目录配置，对应 {@code app.public-download.dir}。
 *
 * <p><b>默认打进 jar</b>：值为 {@code classpath:/public-download/}（文件在
 * {@code src/main/resources/public-download/}），开发与 jar 包启动都能下载。
 *
 * <p><b>生产覆盖</b>：想改成磁盘目录（便于不重新打包就增删文件），把它设成绝对路径，
 * 例如 {@code /data/public-download} 或 {@code X:/data/public-download}。
 *
 * <p>通过 {@code GET /ai-api/public/**} 下载，路径为文件相对该目录的路径（可含子目录，
 * 如 {@code skills/code-interpreter.zip}），控制器内部做了路径穿越校验。
 * 目录内容默认全部公开可读，勿放敏感资料。
 */
@ConfigurationProperties(prefix = "app.public-download")
public class PublicDownloadProperties {

    /** 公开资料根目录：classpath 位置（打进 jar）或文件系统绝对路径。 */
    private String dir = "classpath:/public-download/";

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }
}
