package com.example.chat.sandbox;

import com.example.chat.artifact.ArtifactRef;
import com.example.chat.artifact.ArtifactStorage;
import io.github.markpollack.sandbox.Sandbox;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Lets a skill script hand a generated file (image, PDF, xlsx, zip, ...) back
 * to the user. The tool copies the file out of the sandbox, stages it on the
 * host, and returns a structured reference whose {@code url} the LLM can include
 * in its reply for the user to click.
 *
 * <p>One instance per chat request — bound to that request's sandbox so the
 * tool cannot cross-contaminate sessions.
 */
public class SandboxArtifactTool {

    private final Sandbox sandbox;
    private final ArtifactStorage storage;

    public SandboxArtifactTool(Sandbox sandbox, ArtifactStorage storage) {
        this.sandbox = sandbox;
        this.storage = storage;
    }

    // @formatter:off
    @Tool(name = "ExportArtifact", description = """
        Publish a file generated inside the sandbox so the user can download it.
        Use this for any binary or large output (image, PDF, xlsx, zip, csv) — do NOT use Read for those.

        Returns: { id, filename, mimeType, size, url }. Embed the url in your reply
        (e.g. as a markdown link or image) so the user can open it.

        Usage:
        - file_path MUST be the file's full path inside the sandbox. Two equivalent forms accepted:
          absolute "/work/skills-cache/<...>/output/chart.png" or its bare suffix
          "skills-cache/<...>/output/chart.png". Both resolve to the same file.
        - DO NOT pass a script-relative path like "output/chart.png" alone. Skill scripts typically
          run after `cd <skill base directory>`, so their stdout paths are rooted at that base —
          you must prepend the skill base directory before calling this tool.
        - If you don't know where the file landed (e.g. the script printed only a bare filename),
          run Bash `find /work -name '<filename>' -type f 2>/dev/null` first and feed the absolute
          path returned. Do NOT guess paths.
        - Optional mime_type overrides auto-detection by file extension.
        - File size is capped server-side; oversize exports return an error string.
        """)
    public Object exportArtifact(
        @ToolParam(description = "Full sandbox path of the file to export (e.g. \"/work/skills-cache/<...>/output/chart.png\" or its bare suffix \"skills-cache/<...>/output/chart.png\"). NOT a script-relative path like \"output/chart.png\".") String filePath,
        @ToolParam(description = "Optional MIME type, e.g. image/png. Inferred from extension when omitted.", required = false) String mimeType) {
        // @formatter:on
        try {
            ArtifactRef ref = storage.export(sandbox, filePath, mimeType);
            return ref;
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Error exporting artifact: " + e.getMessage();
        }
    }
}
