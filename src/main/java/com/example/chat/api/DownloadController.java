package com.example.chat.api;

import com.example.chat.artifact.ArtifactStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Serves artifacts staged by {@code ExportArtifact}. The id resolves to a
 * single file under the artifact-dir and is served back with the correct MIME type.
 *
 * <p>Inline-previewable types (HTML/图片/视频/音频/PDF/纯文本…) 用
 * {@code Content-Disposition: inline}，浏览器直接渲染，体验更好；其余一律
 * {@code attachment} 触发下载。URL 里的 {@code {filename}} 段只是装饰
 * （让浏览器另存时带对文件名），实际文件按 id 定位。
 *
 * <p><b>安全说明</b>：HTML 等以 inline 返回时，浏览器会在本服务 origin 下执行其中
 * 的脚本（能读到同源 cookie / storage）。artifact 由受控的 Skill 产物而来，文件名在
 * 入库时已经 URL-encode；若将来接入外部不可信内容，需自行评估是否需要再隔离域。
 */
@RestController
public class DownloadController {

    private final ArtifactStorage storage;

    /**
     * 可直接 inline 预览的 MIME 类型（其余下载）。按「类型」而非「后缀」判断，
     * 与前面 MIME 探测逻辑解耦，也覆盖 .html/.htm 这类多后缀情况。
     */
    private static final Set<String> INLINE_TYPES = Set.of(
            "text/html",
            "text/plain",
            "text/css",
            "application/javascript",
            "application/json",
            "application/xml",
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/svg+xml",
            "video/mp4",
            "video/webm",
            "audio/mpeg",
            "audio/ogg",
            "application/pdf"
    );

    public DownloadController(ArtifactStorage storage) {
        this.storage = storage;
    }

    @GetMapping("/download/{id}/{filename:.+}")
    public ResponseEntity<Resource> download(@PathVariable String id, @PathVariable String filename) {
        Optional<Path> file = storage.resolve(id);
        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Path actual = file.get();

        MediaType mediaType = resolveMediaType(actual);
        boolean inline = INLINE_TYPES.contains(mediaType.getType() + "/" + mediaType.getSubtype());

        ContentDisposition cd = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(actual.getFileName().toString())
                .build();

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString());

        long length;
        try {
            length = Files.size(actual);
        } catch (IOException e) {
            length = -1;
        }
        if (length >= 0) {
            builder.contentLength(length);
        }
        return builder.body(new FileSystemResource(actual));
    }

    /** 探测 MIME：先按内容探（Files.probeContentType），再按扩展名猜，兜底 octet-stream。 */
    private static MediaType resolveMediaType(Path file) {
        try {
            String probed = Files.probeContentType(file);
            if (probed == null) {
                probed = URLConnection.guessContentTypeFromName(file.getFileName().toString());
            }
            return (probed != null) ? MediaType.parseMediaType(probed) : MediaType.APPLICATION_OCTET_STREAM;
        } catch (IOException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
