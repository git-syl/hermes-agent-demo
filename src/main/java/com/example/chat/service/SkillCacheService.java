package com.example.chat.service;

import com.example.chat.api.dto.ChatRequest.SkillRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads skill zip archives and extracts them into a per-user/per-assistant/per-session
 * cache directory. Returns {@link Resource} handles to the extracted skill directories,
 * which can be fed into {@code SkillsTool.builder().addSkillsResources(...)}.
 *
 * <p>缓存目录布局：
 * <ul>
 *   <li>{@code assistantId} 非空：{@code <root>/<userId>/<assistantId>/<sessionId>/<name>-<urlHash>}</li>
 *   <li>{@code assistantId} 为空：{@code <root>/<userId>/<sessionId>/<name>-<urlHash>}</li>
 * </ul>
 * 调用方上游若用按用户内编号的 {@code sessionId}（同一 userId 在不同助手下可能复用同一个
 * sessionId），加上 {@code assistantId} 这一层可避免两个助手的 skill 解压目录互相覆盖。
 */
@Service
public class SkillCacheService {

    private static final Logger log = LoggerFactory.getLogger(SkillCacheService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final Path cacheRoot;

    public SkillCacheService(@Value("${app.skills.cache-dir}") String cacheDir) {
        this.cacheRoot = Path.of(cacheDir).toAbsolutePath().normalize();
        // 启动即建目录，缺权限就 fail-fast 给出具体的运维命令 —— 比启动后第一次请求才报错好排查。
        // 父路径 /work 通常需要 root 一次性 install 出来，应用账号无权创建顶层目录。
        try {
            Files.createDirectories(this.cacheRoot);
        } catch (IOException e) {
            throw new IllegalStateException(("""
                    无法创建 skills 缓存目录: %s
                    父目录可能不存在或当前用户无写权限。一次性准备命令：
                      Linux:   sudo install -d -o $(id -un) -g $(id -gn) -m 750 /work
                      Windows: mkdir <盘符>:\\work        (PowerShell 或管理员 cmd)""")
                    .formatted(this.cacheRoot), e);
        }
    }

    public List<Resource> resolve(Long userId, Long assistantId, String sessionId, List<SkillRef> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        String userKey = userId == null ? "anonymous" : String.valueOf(userId);
        String assistantKey = assistantId == null ? null : String.valueOf(assistantId);
        String sessionKey = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;

        List<Resource> resources = new ArrayList<>(skills.size());
        for (SkillRef ref : skills) {
            if (ref == null || ref.name() == null || ref.url() == null) {
                continue;
            }
            try {
                Path skillDir = ensureSkill(userKey, assistantKey, sessionKey, ref);
                resources.add(new FileSystemResource(skillDir.toFile()));
            } catch (InterruptedException e) {
                // 请求被取消等场景：恢复中断标志并停止后续 skill 处理，避免吞掉中断语义。
                Thread.currentThread().interrupt();
                log.warn("Interrupted while resolving skill '{}' from {}; skipping remaining skills",
                        ref.name(), ref.url());
                break;
            } catch (Exception e) {
                // 单个 skill 下载/解压失败时不打断整次请求：记录告警后跳过，
                // 其余可用 skill 继续生效；全都失败则返回空列表，由上游用空目录构造 sandbox。
                log.warn("Skipping skill '{}' from {} due to error: {}",
                        ref.name(), ref.url(), e.getMessage());
            }
        }
        return resources;
    }

    private Path ensureSkill(String userKey, String assistantKey, String sessionKey, SkillRef ref)
            throws IOException, InterruptedException {
        String urlHash = sha256Hex(ref.url()).substring(0, 12);
        Path userDir = cacheRoot.resolve(sanitize(userKey));
        Path scopeDir = assistantKey == null ? userDir : userDir.resolve(sanitize(assistantKey));
        Path skillDir = scopeDir
                .resolve(sanitize(sessionKey))
                .resolve(sanitize(ref.name()) + "-" + urlHash);

        if (Files.isDirectory(skillDir) && Files.list(skillDir).findAny().isPresent()) {
            log.debug("Skill cache hit: {}", skillDir);
            return skillDir;
        }
        Files.createDirectories(skillDir);

        Path zipFile = Files.createTempFile("skill-", ".zip");
        try {
            log.info("Downloading skill '{}' from {}", ref.name(), ref.url());
            HttpRequest req = HttpRequest.newBuilder(URI.create(ref.url()))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<Path> resp = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofFile(zipFile,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING));
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode() + " when downloading " + ref.url());
            }
            unzip(zipFile, skillDir);
            return skillDir;
        } finally {
            Files.deleteIfExists(zipFile);
        }
    }

    private static void unzip(Path zipFile, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (InputStream in = Files.newInputStream(zipFile);
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path resolved = normalizedTarget.resolve(entry.getName()).normalize();
                // zip-slip guard
                if (!resolved.startsWith(normalizedTarget)) {
                    throw new IOException("Refusing to extract entry outside target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zin, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zin.closeEntry();
            }
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
