package com.example.chat.sandbox;

import io.github.markpollack.sandbox.Sandbox;
import io.github.markpollack.sandbox.SandboxException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 沙箱版 FileSystemTools —— Read / Write / Edit。工具描述、参数签名、返回值文案
 * 严格对齐上游 {@link org.springaicommunity.agent.tools.FileSystemTools}。
 *
 * <p>模型在 {@code file_path} 里给出的"绝对路径"会被 {@link #toRelative} 防御性地裁剪
 * （剥盘符、剥前导 {@code /}、规范化 {@code ..}）后送给沙箱 API。沙箱内 workDir 永远是
 * {@code /work}，模型给的 {@code /work/X} 会被剥成 {@code X}，再由沙箱重新加上 {@code /work}
 * 前缀 —— 路径在 work/ 段之后保持一致，跟 host 端缓存目录完全对齐。
 *
 * <p>One instance per chat request, bound to that request's sandbox.
 */
public class SandboxFileSystemTools {

    private static final int MAX_LINES_DEFAULT = 2000;
    private static final int MAX_LINE_LEN = 2000;

    private final Sandbox sandbox;

    public SandboxFileSystemTools(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    // @formatter:off
    @Tool(name = "Read", description = """
        Reads a file from the local filesystem. You can access any file directly by using this tool.
        It is okay to read a file that does not exist; an error will be returned.

        Usage:
        - The file_path parameter must be an absolute path, not a relative path
        - By default, it reads up to 2000 lines starting from the beginning of the file
        - You can optionally specify a line offset and limit (especially handy for long files), but it's recommended to read the whole file by not providing these parameters
        - Any lines longer than 2000 characters will be truncated
        - Results are returned using cat -n format, with line numbers starting at 1
        - This tool can only read files, not directories. To read a directory, use an ls command via the Bash tool.
        - If you read a file that exists but has empty contents you will receive a system reminder warning in place of file contents.
        """)
    public String read(
        @ToolParam(description = "The absolute path to the file to read") String filePath,
        @ToolParam(description = "The line number to start reading from. Only provide if the file is too large to read at once", required = false) Integer offset,
        @ToolParam(description = "The number of lines to read. Only provide if the file is too large to read at once.", required = false) Integer limit) {
        // @formatter:on
        String rel = SandboxPaths.toRelative(filePath);
        if (!sandbox.files().exists(rel)) {
            return "Error: File does not exist: " + filePath;
        }
        String content;
        try {
            content = sandbox.files().read(rel);
        } catch (SandboxException e) {
            return "Error: " + e.getMessage();
        }

        // split("\n", -1) 保留末尾空段（"a\n" → ["a", ""]）；总行数算 "可见行"，
        // 把这种末尾尾随换行带出来的空段减掉，否则文件末尾每个 \n 都会让 total 多 1。
        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;
        if (totalLines > 0 && lines[totalLines - 1].isEmpty()) {
            totalLines--;
        }
        if (totalLines == 0) {
            return "File is empty: " + filePath;
        }

        int start = (offset != null && offset > 0) ? offset : 1;
        int max = (limit != null && limit > 0) ? limit : MAX_LINES_DEFAULT;
        if (start > totalLines) {
            return String.format("No lines to read. File has %d lines, but offset was %d", totalLines, start);
        }

        StringBuilder body = new StringBuilder();
        int emitted = 0;
        for (int i = start - 1; i < totalLines && emitted < max; i++, emitted++) {
            String line = lines[i];
            if (line.length() > MAX_LINE_LEN) {
                line = line.substring(0, MAX_LINE_LEN) + "... (line truncated)";
            }
            body.append(String.format("%6d\t", i + 1)).append(line).append('\n');
        }

        // 头部跟上游 FileSystemTools.read 完全对齐：File: 路径 + Showing lines X-Y of TOTAL + 空行。
        StringBuilder out = new StringBuilder();
        out.append("File: ").append(filePath).append('\n');
        out.append(String.format("Showing lines %d-%d of %d%n%n", start, start + emitted - 1, totalLines));
        out.append(body);
        return out.toString();
    }

    // @formatter:off
    @Tool(name = "Write", description = """
        Writes a file to the local filesystem.

        Usage:
        - This tool will overwrite the existing file if there is one at the provided path.
        - If this is an existing file, you MUST use the Read tool first to read the file's contents. This tool will fail if you did not read the file first.
        - ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
        - NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested by the User.
        - Only use emojis if the user explicitly requests it. Avoid writing emojis to files unless asked.
        """)
    public String write(
        @ToolParam(description = "The absolute path to the file to write (must be absolute, not relative)") String filePath,
        @ToolParam(description = "The content to write to the file") String content) {
        // @formatter:on
        String rel = SandboxPaths.toRelative(filePath);
        String body = content == null ? "" : content;
        // 用上游一致的口径：字节数实际是 char 数（跟 String.length() 对齐），路径用模型传入的 filePath
        // 而非剥过前缀的 rel，避免渲染出 "skills-cache/foo.py" 这种跟模型输入不一致的回执。
        boolean existed = sandbox.files().exists(rel);
        try {
            sandbox.files().create(rel, body);
        } catch (SandboxException e) {
            return "Error writing file: " + e.getMessage();
        }
        return String.format("Successfully %s file: %s (%d bytes)",
                existed ? "overwrote" : "created", filePath, body.length());
    }

    // @formatter:off
    // 形参名故意用 snake_case（违反 Java 命名规约，但 @SuppressWarnings 仅在编译器告警时需要）：
    // Spring AI ToolCallback 把 Java 形参名直接写进 JSON schema，模型看到的字段名就是这些；
    // description 文案里全部引用 old_string / new_string / replace_all，必须保持一致，否则
    // 模型会按 description 填 snake_case 字段，schema 却暴露 camelCase，工具调用直接报错。
    @Tool(name = "Edit", description = """
        Performs exact string replacements in files.

        Usage:
        - You must use your `Read` tool at least once in the conversation before editing. This tool will error if you attempt an edit without reading the file.
        - When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as it appears AFTER the line number prefix. The line number prefix format is: spaces + line number + tab. Everything after that tab is the actual file content to match. Never include any part of the line number prefix in the old_string or new_string.
        - ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
        - Only use emojis if the user explicitly requests it. Avoid adding emojis to files unless asked.
        - The edit will FAIL if `old_string` is not unique in the file. Either provide a larger string with more surrounding context to make it unique or use `replace_all` to change every instance of `old_string`.
        - Use `replace_all` for replacing and renaming strings across the file. This parameter is useful if you want to rename a variable for instance.
        """)
    @SuppressWarnings({"checkstyle:ParameterName", "PMD.MethodNamingConventions", "PMD.FormalParameterNamingConventions"})
    public String edit(
        @ToolParam(description = "The absolute path to the file to modify") String filePath,
        @ToolParam(description = "The text to replace") String old_string,
        @ToolParam(description = "The text to replace it with (must be different from old_string)") String new_string,
        @ToolParam(description = "Replace all occurences of old_string (default false)", required = false) Boolean replace_all) {
        // @formatter:on
        String rel = SandboxPaths.toRelative(filePath);
        if (!sandbox.files().exists(rel)) {
            return "Error: File does not exist: " + filePath;
        }
        if (old_string == null || old_string.isEmpty()) {
            return "Error: old_string must not be empty";
        }
        String replacement = new_string == null ? "" : new_string;
        if (old_string.equals(replacement)) {
            return "Error: old_string and new_string must be different";
        }

        String content;
        try {
            content = sandbox.files().read(rel);
        } catch (SandboxException e) {
            return "Error reading file: " + e.getMessage();
        }

        int occurrences = countOccurrences(content, old_string);
        if (occurrences == 0) {
            return "Error: old_string not found in file: " + filePath;
        }
        boolean replaceAll = Boolean.TRUE.equals(replace_all);
        if (!replaceAll && occurrences > 1) {
            return String.format(
                    "Error: old_string appears %d times in the file. Either provide a larger string with more surrounding context to make it unique or use replace_all=true to change all instances.",
                    occurrences);
        }

        String updated;
        if (replaceAll) {
            updated = content.replace(old_string, replacement);
        } else {
            int idx = content.indexOf(old_string);
            updated = content.substring(0, idx) + replacement + content.substring(idx + old_string.length());
        }

        try {
            sandbox.files().create(rel, updated);
        } catch (SandboxException e) {
            return "Error writing file: " + e.getMessage();
        }

        String snippet = generateEditSnippet(updated, replacement);
        return String.format(
                "The file %s has been updated. Here's the result of running `cat -n` on a snippet of the edited file:%n%s",
                filePath, snippet);
    }

    /**
     * 数 substring 出现次数（不重叠）。等价于上游 {@code FileSystemTools.countOccurrences}。
     */
    private static int countOccurrences(String text, String substring) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }

    /**
     * 生成编辑后片段（cat -n 风格，{@code <行号>→<行内容>}），上下文 ±5 行。
     * 找不到 {@code newString} 命中行时回退到文件开头前 11 行。
     * 对齐上游 {@code FileSystemTools.generateEditSnippet}。
     */
    private static String generateEditSnippet(String fileContent, String newString) {
        String[] lines = fileContent.split("\n", -1);
        String[] newLines = newString.split("\n", -1);

        int editStartLine = -1;
        int editEndLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (newLines.length > 0 && lines[i].contains(newLines[0])) {
                boolean matches = true;
                for (int j = 1; j < newLines.length && i + j < lines.length; j++) {
                    if (!lines[i + j].contains(newLines[j])) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    editStartLine = i;
                    editEndLine = i + newLines.length - 1;
                    break;
                }
            }
        }
        if (editStartLine == -1) {
            editStartLine = 0;
            editEndLine = Math.min(10, lines.length - 1);
        }

        int contextBefore = 5;
        int contextAfter = 5;
        int startLine = Math.max(0, editStartLine - contextBefore);
        int endLine = Math.min(lines.length - 1, editEndLine + contextAfter);

        StringBuilder snippet = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            snippet.append(String.format("%6d→%s", i + 1, lines[i]));
            if (i < endLine) {
                snippet.append('\n');
            }
        }
        return snippet.toString();
    }

}
