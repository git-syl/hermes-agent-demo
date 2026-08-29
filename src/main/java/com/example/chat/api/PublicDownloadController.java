package com.example.chat.api;

import com.example.chat.config.PublicDownloadProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Path;

/**
 * 公开资料下载：把 {@code app.public-download.dir} 下的文件以
 * {@code GET /ai-api/public/<路径>} 暴露出去。
 *
 * <p>{@code <路径>} 是文件相对根目录的路径，可含子目录（如 {@code skills/code-interpreter.zip}；
 * 前端直接拼真实文件名即可，浏览器另存为默认取 URL 最后一段）。所有响应均为 {@code attachment} 下载。
 *
 * <p><b>两种目录来源</b>（见 {@link PublicDownloadProperties}）：
 * <ul>
 *   <li>{@code classpath:/...}（默认）：打进 jar，用 {@link ResourceLoader} 读，开发与 jar 启动通用；</li>
 *   <li>绝对/相对文件系统路径：走磁盘目录，便于不重新打包增删文件。</li>
 * </ul>
 *
 * <p><b>安全</b>：路径做穿越校验，拒绝 {@code ..} 与越出根目录；响应文件名取自真实资源，
 * 不信任 URL 段。目录内容默认全部公开可读，勿放敏感资料。
 */
@RestController
@EnableConfigurationProperties(PublicDownloadProperties.class)
public class PublicDownloadController {

    private final ResourceLoader resourceLoader;
    private final String dir;
    private final Path fsRoot;   // 非 classpath 时的文件系统根目录

    public PublicDownloadController(PublicDownloadProperties props, ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.dir = props.getDir();
        this.fsRoot = dir.startsWith("classpath:") ? null : Path.of(dir).toAbsolutePath().normalize();
    }

    /**
     * {@code {*path}} 是 PathPatternParser 的「捕获剩余路径」写法，可含多层子目录
     * （如 {@code skills/code-interpreter.zip}）。前端直接拼真实文件相对路径即可，
     * 浏览器另存为默认取 URL 最后一段。
     *
     * <p>注意：PathPatternParser 的 {@code {*path}} 捕获值自带前导 {@code /}
     * （如 {@code /skills/code-interpreter.zip}），这里剥掉再按相对路径解析。
     */
    @GetMapping("/public/{*path}")
    public ResponseEntity<Resource> download(@PathVariable String path) {
        if (path == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Resource file = resolveSafely(rel);
        if (file == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return serve(file, rel);
    }

    /** 解析并做穿越校验：拒绝 ..、反斜杠、绝对路径；越出根目录返回 null。 */
    private Resource resolveSafely(String rel) {
        if (rel.contains("..") || rel.contains("\\") || rel.startsWith("/")) {
            return null;
        }
        if (fsRoot != null) {
            Path candidate = fsRoot.resolve(rel).normalize();
            if (!candidate.startsWith(fsRoot) || !java.nio.file.Files.isRegularFile(candidate)) {
                return null;
            }
            return new FileSystemResource(candidate);
        }
        // classpath 模式：ResourceLoader 自带解析，jar 内也能读。
        String base = dir.endsWith("/") ? dir : dir + "/";
        Resource r = resourceLoader.getResource(base + rel);
        return (r.exists() && r.isReadable()) ? r : null;
    }

    private ResponseEntity<Resource> serve(Resource file, String rel) {
        String name = file.getFilename();
        if (name == null || name.isBlank()) {
            name = rel.substring(rel.lastIndexOf('/') + 1);
        }
        MediaType mediaType = guessMediaType(name);
        ContentDisposition cd = ContentDisposition.attachment().filename(name).build();

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString());

        long length = contentLength(file);
        if (length >= 0) {
            builder.contentLength(length);
        }
        return builder.body(file);
    }

    private static long contentLength(Resource file) {
        try {
            return file.contentLength();
        } catch (IOException e) {
            return -1;
        }
    }

    private static MediaType guessMediaType(String filename) {
        String probed = URLConnection.guessContentTypeFromName(filename);
        return (probed != null) ? MediaType.parseMediaType(probed) : MediaType.APPLICATION_OCTET_STREAM;
    }
}
