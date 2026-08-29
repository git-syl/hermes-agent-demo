package com.example.chat.artifact;

import com.example.chat.config.ArtifactProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.sandbox.LocalSandbox;
import io.github.markpollack.sandbox.Sandbox;
import io.github.markpollack.sandbox.docker.DockerSandbox;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Stages files copied out of a {@link Sandbox} on the host filesystem and
 * builds a download URL for them.
 *
 * <p>Layout: {@code <artifacts.dir>/<artifactId>/<filename>}. Each artifact gets
 * its own UUID directory, so the download endpoint only needs the id and can
 * resolve the actual filename by listing that directory — no path traversal
 * possible from the URL.
 *
 * <p>Stub for object storage: when OSS/S3 lands, change {@link #publish} to
 * upload and return the remote URL instead of the local one. Everything else
 * (tool, controller fallback) stays the same.
 */
@Service
public class ArtifactStorage {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStorage.class);

    private final ArtifactProperties props;

    public ArtifactStorage(ArtifactProperties props) {
        this.props = props;
    }

    /**
     * Copy {@code sandboxRelativePath} out of {@code sandbox} into the staging
     * directory and return a public reference.
     *
     * @throws IllegalArgumentException if the file does not exist, exceeds the
     *                                  size limit, or the path escapes the sandbox
     * @throws IllegalStateException    on I/O failure
     */
    public ArtifactRef export(Sandbox sandbox, String sandboxRelativePath, String mimeType) {
        if (sandboxRelativePath == null || sandboxRelativePath.isBlank()) {
            throw new IllegalArgumentException("sandbox path must not be empty");
        }
        String rel = normalize(sandboxRelativePath);

        if (!sandbox.files().exists(rel)) {
            throw new IllegalArgumentException("Artifact not found in sandbox: " + rel);
        }

        String filename = Path.of(rel).getFileName().toString();
        String id = UUID.randomUUID().toString();
        Path destDir = Path.of(props.getDir(), id);
        Path destFile = destDir.resolve(filename);

        try {
            Files.createDirectories(destDir);
            copyFromSandbox(sandbox, rel, destFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy artifact out of sandbox: " + e.getMessage(), e);
        }

        long size;
        try {
            size = Files.size(destFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stat exported artifact: " + e.getMessage(), e);
        }
        if (size > props.getMaxSizeBytes()) {
            try {
                Files.deleteIfExists(destFile);
                Files.deleteIfExists(destDir);
            } catch (IOException ignored) {
            }
            throw new IllegalArgumentException("Artifact exceeds max size " + props.getMaxSizeBytes()
                    + " bytes (got " + size + ")");
        }

        String resolvedMime = (mimeType != null && !mimeType.isBlank()) ? mimeType : guessMime(filename);
        String url = publish(id, filename);
        log.info("Exported artifact id={} file={} size={} mime={}", id, filename, size, resolvedMime);
        return new ArtifactRef(id, filename, resolvedMime, size, url);
    }

    /**
     * Resolve an artifact id to its on-disk file. Used by the download controller.
     * Returns empty if id does not exist or the directory has no file.
     */
    public Optional<Path> resolve(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("\\") || id.contains("..")) {
            return Optional.empty();
        }
        Path dir = Path.of(props.getDir(), id);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).findFirst();
        } catch (IOException e) {
            log.warn("Failed to resolve artifact id={}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Build the public URL. Today: local download endpoint. Swap this for the
     * OSS/S3 URL when remote storage is wired in.
     * The {@code /ai-api} prefix matches the global API path prefix applied by
     * {@code WebConfig#configurePathMatch} (addPathPrefix).
     */
    private String publish(String id, String filename) {
        String safeName = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        String base = props.getBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/ai-api/download/" + id + "/" + safeName;
    }

    /**
     * Copy a file out of the sandbox. We use backend-specific paths because the
     * common {@link io.github.markpollack.sandbox.SandboxFiles} API only exposes
     * String-based read which would corrupt binary content.
     */
    private void copyFromSandbox(Sandbox sandbox, String rel, Path dest) throws IOException {
        if (sandbox instanceof DockerSandbox docker) {
            docker.getContainer().copyFileFromContainer("/work/" + rel, dest.toString());
        } else if (sandbox instanceof LocalSandbox local) {
            Path src = local.workDir().resolve(rel);
            Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            throw new IOException("Unsupported sandbox backend for binary export: " + sandbox.getClass().getName());
        }
    }

    private static String normalize(String p) {
        String s = p.replace('\\', '/').trim();
        // Windows 盘符剥离：模型偶尔会把宿主原始路径（X:/work/... 或 X:\work\...）原样回填,
        // 沙箱里没有盘符锚点，必须先剥掉。跟 SandboxFileSystemTools.toRelative 行为对齐,
        // 避免 ExportArtifact 在两套工具之间出现归一不一致。
        if (s.length() >= 2 && s.charAt(1) == ':') {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        // 沙箱 workDir = /work。模型常按 prompt 翻译规则传 "/work/output/foo.png" 这种沙箱绝对路径,
        // 剥前导 / 后是 "work/output/foo.png"; 不剥这层会被 sandbox.files() 再拼 /work/ → /work/work/...,
        // 跟 SandboxFileSystemTools.toRelative 同一套归一逻辑保持一致。
        if (s.equals("work")) {
            s = "";
        } else if (s.startsWith("work/")) {
            s = s.substring("work/".length());
        }
        Path n = Path.of(s).normalize();
        while (n.getNameCount() > 0 && "..".equals(n.getName(0).toString())) {
            n = n.subpath(1, n.getNameCount());
        }
        return n.toString().replace('\\', '/');
    }

    private static String guessMime(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
    }
}
