package com.example.chat.sandbox;

import com.example.chat.config.SandboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.sandbox.LocalSandbox;
import io.github.markpollack.sandbox.Sandbox;
import io.github.markpollack.sandbox.docker.DockerSandbox;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds a fresh {@link Sandbox} for each chat request and populates it with the
 * resolved skill files.
 *
 * <p>Why per-request: every request can carry a different set of skills plus
 * potentially-malicious user input that ends up driving {@code Bash} / {@code Write}
 * tool calls. A dedicated short-lived sandbox per request gives us
 * <ol>
 *     <li>isolation between concurrent users / sessions,</li>
 *     <li>deterministic cleanup (the container / temp dir dies with the request),</li>
 *     <li>no shared state to leak skill content or working files across requests.</li>
 * </ol>
 *
 * <p><b>Path alignment (scheme F-2)</b>：宿主机 cache 与沙箱内 workDir 都以 {@code /work/}
 * 为锚点。host 路径里 {@code work/} 段之后的子路径，被原样用作沙箱内的相对路径，于是
 * SkillsTool 给模型的 host basePath 与脚本在沙箱里能 {@code cd} 进去的路径一致：
 * <pre>
 * Linux  host: /work/skills-cache/&lt;u&gt;/&lt;a&gt;/&lt;s&gt;/&lt;name&gt;-&lt;hash&gt;/   ──┐
 * sandbox:    /work/skills-cache/&lt;u&gt;/&lt;a&gt;/&lt;s&gt;/&lt;name&gt;-&lt;hash&gt;/   ──┘ 等价
 * Windows host: X:\work\skills-cache\&lt;u&gt;\&lt;a&gt;\&lt;s&gt;\&lt;name&gt;-&lt;hash&gt;\  ──┐
 * sandbox:     /work/skills-cache/&lt;u&gt;/&lt;a&gt;/&lt;s&gt;/&lt;name&gt;-&lt;hash&gt;/   ──┘ 模型按 prompt 规则翻译
 * </pre>
 */
@Component
public class SandboxFactory {

    private static final Logger log = LoggerFactory.getLogger(SandboxFactory.class);

    private static final long MAX_SKILL_FILE_BYTES = 5L * 1024 * 1024; // 5 MiB per file

    private final SandboxProperties props;

    public SandboxFactory(SandboxProperties props) {
        this.props = props;
    }

    /**
     * Create a new sandbox and copy each resolved skill directory into
     * {@code <workDir>/skills/<skill-dir-name>/} inside the sandbox.
     *
     * <p>Caller MUST close the returned sandbox (e.g. via try-with-resources).
     */
    public Sandbox create(List<Resource> skillDirs) {
        Sandbox sandbox = switch (props.getMode()) {
            case DOCKER -> DockerSandbox.builder().image(props.getImage()).build();
            case LOCAL -> {
                log.warn("Sandbox mode=LOCAL provides NO process isolation. Use DOCKER for untrusted skills.");
                yield LocalSandbox.builder().tempDirectory("chat-sandbox-").build();
            }
        };

        try {
            if (skillDirs != null && !skillDirs.isEmpty()) {
                for (Resource skillDir : skillDirs) {
                    copySkillDir(sandbox, skillDir);
                }
            }
            return sandbox;
        } catch (RuntimeException e) {
            // Don't leak the container/tempdir if seeding failed.
            try {
                sandbox.close();
            } catch (Exception closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }
    }

    private void copySkillDir(Sandbox sandbox, Resource skillDirRes) {
        Path hostDir;
        try {
            hostDir = skillDirRes.getFile().toPath();
        } catch (IOException e) {
            throw new IllegalStateException("Skill resource is not a filesystem directory: " + skillDirRes, e);
        }
        if (!Files.isDirectory(hostDir)) {
            log.warn("Skill resource is not a directory, skipping: {}", hostDir);
            return;
        }

        // host 路径里第一个 `work` 段之后的子路径，就是沙箱内的相对路径 ——
        // 沙箱 workDir 永远是 /work，相对路径接在它后面后，host 与沙箱路径在 work/ 段之后完全相同。
        String top = subPathAfterWork(hostDir);

        try (Stream<Path> stream = Files.walk(hostDir)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String rel = top + "/" + hostDir.relativize(file).toString().replace('\\', '/');
                long size;
                try {
                    size = Files.size(file);
                } catch (IOException e) {
                    log.warn("Skipping skill file (stat failed): {} — {}", file, e.getMessage());
                    return;
                }
                if (size > MAX_SKILL_FILE_BYTES) {
                    log.warn("Skipping oversize skill file ({} bytes > {}): {}",
                            size, MAX_SKILL_FILE_BYTES, file);
                    return;
                }
                String content;
                try {
                    // Skills are intended to be text (scripts, markdown, configs). Binary blobs
                    // would round-trip lossy through SandboxFiles.create — accepted trade-off.
                    content = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("Skipping skill file (read failed, likely binary): {} — {}", file, e.getMessage());
                    return;
                }
                sandbox.files().create(rel, content);
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk skill directory: " + hostDir, e);
        }
        log.debug("Seeded sandbox with skill dir: {} -> {}", hostDir, top);
    }

    /**
     * 提取 host 绝对路径里第一个 {@code work} 段之后的子路径作为沙箱内的相对路径。
     * <p>e.g. {@code X:\work\skills-cache\\u\a\s\pdf-abc123} → {@code skills-cache/u/a/s/pdf-abc123}。
     * <p>找不到 {@code work} 段直接 fail-fast —— 配置或运维问题，必须早暴露。
     */
    private static String subPathAfterWork(Path hostDir) {
        Path abs = hostDir.toAbsolutePath().normalize();
        for (int i = 0; i < abs.getNameCount(); i++) {
            if ("work".equals(abs.getName(i).toString())) {
                if (i + 1 >= abs.getNameCount()) {
                    throw new IllegalStateException(
                            "Skill directory points exactly at 'work/' with no sub-path: " + abs);
                }
                return abs.subpath(i + 1, abs.getNameCount())
                        .toString().replace('\\', '/');
            }
        }
        throw new IllegalStateException(
                "Skill cache must live under a 'work/' directory for host↔sandbox alignment, got: " + abs
                + ". Check app.skills.cache-dir in application.yaml.");
    }
}
