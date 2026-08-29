package com.example.chat.service;

import com.example.chat.api.dto.ChatRequest.SubagentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Downloads Claude-style subagent markdown files (one {@code .md} per agent) into a
 * per-user/per-assistant/per-session cache directory and returns {@link Resource} handles
 * ready to feed into {@code ClaudeSubagentReferences.fromResources(...)}.
 *
 * <p>与 {@link SkillCacheService} 的关键差异：
 * <ul>
 *   <li>Skill 是"目录型资源"（含脚本、依赖锁文件等），必须以 zip 打包、下载后<u>解压成目录</u>；</li>
 *   <li>子代理是"单文件型资源"（YAML frontmatter + system prompt 正文，无附属文件），
 *       直接下载单个 {@code .md} 文件即可，<u>不需要解压</u>。</li>
 * </ul>
 * 因此本类比 {@code SkillCacheService} 少了 zip 下载/解压/zip-slip 防护逻辑，缓存布局也从
 * 目录级（{@code <name>-<urlHash>/}）改为文件级（{@code <name>-<urlHash>.md}）。
 *
 * <p>缓存目录布局：
 * <ul>
 *   <li>{@code assistantId} 非空：{@code <root>/<userId>/<assistantId>/<sessionId>/<name>-<urlHash>.md}</li>
 *   <li>{@code assistantId} 为空：{@code <root>/<userId>/<sessionId>/<name>-<urlHash>.md}</li>
 * </ul>
 * 与 skills 一致地按 {@code (userId, assistantId, sessionId)} 分桶，避免同一用户在不同助手下
 * 复用同一个 sessionId 时缓存互相覆盖。
 *
 * <p>缓存语义：同 URL 永久缓存，URL 变更通过 {@code urlHash} 自然换文件；无 TTL / 无 ETag。
 * 与 {@code SkillCacheService} 保持一致。
 */
@Service
public class AgentCacheService {

    private static final Logger log = LoggerFactory.getLogger(AgentCacheService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final Path cacheRoot;

    public AgentCacheService(@Value("${app.agents.cache-dir}") String cacheDir) {
        this.cacheRoot = Path.of(cacheDir).toAbsolutePath().normalize();
        // 启动即建目录，缺权限 fail-fast 给出运维命令 —— 与 SkillCacheService 同策略。
        try {
            Files.createDirectories(this.cacheRoot);
        } catch (IOException e) {
            throw new IllegalStateException(("""
                    无法创建 agents 缓存目录: %s
                    父目录可能不存在或当前用户无写权限。一次性准备命令：
                      Linux:   sudo install -d -o $(id -un) -g $(id -gn) -m 750 /work
                      Windows: mkdir <盘符>:\\work        (PowerShell 或管理员 cmd)""")
                    .formatted(this.cacheRoot), e);
        }
    }

    /**
     * 按请求维度解析所有 agent 引用，返回 1:1 对应的 {@code .md} 文件 {@link Resource} 列表。
     *
     * <p>单个 agent 下载失败时不打断整次请求：记录告警后跳过，其余可用 agent 继续；
     * 全都失败则返回空列表，由上游跳过 {@code TaskTool} 装配（与 skills 路径同构）。
     *
     * @param userId       用户 ID，null 视为 "anonymous"
     * @param assistantId  助手 ID，null 表示不分桶
     * @param sessionId    会话 ID，空视为 "default"
     * @param subagents   子代理引用列表，null/空返回空列表
     * @return 与 {@code subagents} 入参顺序一致的 {@code .md} 文件 Resource 列表（失败的条目被跳过）
     */
    public List<Resource> resolve(Long userId, Long assistantId, String sessionId, List<SubagentRef> subagents) {
        if (subagents == null || subagents.isEmpty()) {
            return List.of();
        }
        String userKey = userId == null ? "anonymous" : String.valueOf(userId);
        String assistantKey = assistantId == null ? null : String.valueOf(assistantId);
        String sessionKey = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;

        List<Resource> resources = new ArrayList<>(subagents.size());
        for (SubagentRef ref : subagents) {
            if (ref == null || ref.name() == null || ref.url() == null) {
                continue;
            }
            try {
                Path agentFile = ensureAgent(userKey, assistantKey, sessionKey, ref);
                resources.add(new FileSystemResource(agentFile.toFile()));
            } catch (InterruptedException e) {
                // 请求被取消等场景：恢复中断标志并停止后续 agent 处理，避免吞掉中断语义。
                Thread.currentThread().interrupt();
                log.warn("Interrupted while resolving agent '{}' from {}; skipping remaining agents",
                        ref.name(), ref.url());
                break;
            } catch (Exception e) {
                // 单个 agent 下载失败不打断整次请求；全部失败则返回空列表，
                // 由上游跳过 TaskTool 装配。与 SkillCacheService 同策略。
                log.warn("Skipping agent '{}' from {} due to error: {}",
                        ref.name(), ref.url(), e.getMessage());
            }
        }
        return resources;
    }

    /**
     * 下载单个 agent .md 到缓存路径，命中缓存则直接返回。
     *
     * <p>下载失败时<strong>不</strong>留半成品文件 —— {@code Files.writeString} 是原子的，
     * 但 HTTP 失败会在写文件前抛异常；若下载到一半连接断开，{@code BodyHandlers.ofFile} 会
     * 留下不完整文件，最后由 {@code Files.deleteIfExists} 兜底清理。
     */
    private Path ensureAgent(String userKey, String assistantKey, String sessionKey, SubagentRef ref)
            throws IOException, InterruptedException {
        String urlHash = sha256Hex(ref.url()).substring(0, 12);
        Path userDir = cacheRoot.resolve(sanitize(userKey));
        Path scopeDir = assistantKey == null ? userDir : userDir.resolve(sanitize(assistantKey));
        Path sessionDir = scopeDir.resolve(sanitize(sessionKey));
        Path agentFile = sessionDir.resolve(sanitize(ref.name()) + "-" + urlHash + ".md");

        if (Files.isRegularFile(agentFile) && Files.size(agentFile) > 0) {
            log.debug("Agent cache hit: {}", agentFile);
            return agentFile;
        }
        Files.createDirectories(sessionDir);

        log.info("Downloading agent '{}' from {}", ref.name(), ref.url());
        HttpRequest req = HttpRequest.newBuilder(URI.create(ref.url()))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        // ofFile 直接流式落盘；若中途失败文件可能不完整，finally 里删掉。
        HttpResponse<Path> resp = httpClient.send(req,
                HttpResponse.BodyHandlers.ofFile(agentFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
        if (resp.statusCode() / 100 != 2) {
            Files.deleteIfExists(agentFile);
            throw new IOException("HTTP " + resp.statusCode() + " when downloading " + ref.url());
        }
        // 空文件视为下载失败（某些错误页可能 200 但 body 为空），同样清理。
        if (Files.size(agentFile) == 0) {
            Files.deleteIfExists(agentFile);
            throw new IOException("Empty response body when downloading " + ref.url());
        }
        return agentFile;
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
