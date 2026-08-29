package com.example.chat.sandbox;

import io.github.markpollack.sandbox.FileEntry;
import io.github.markpollack.sandbox.Sandbox;
import io.github.markpollack.sandbox.SandboxException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 沙箱版 {@code Grep} —— 按正则在文件内容里搜索，支持 {@code files_with_matches} / {@code count}
 * / {@code content} 三种输出模式、上下文行、行号、大小写、文件类型/glob 过滤、head_limit/offset 分页。
 *
 * <p>工具描述、参数签名、输出格式严格对齐上游 {@link org.springaicommunity.agent.tools.GrepTool}，
 * 唯一差异：上游用宿主机 {@code Files.walk}/{@code Files.newBufferedReader}，本类走 {@link Sandbox#files()}
 * 的非 shell 文件 API（{@code list(rel, maxDepth)} 列举、{@code read(rel)} 读内容）。路径相对沙箱 workDir，
 * {@link SandboxPaths#toRelative} 剥 {@code ..}，故即使 LOCAL 模式也只读沙箱 tempdir，不碰任意宿主路径。
 * 内置 Claude 子代理（Explore/Plan）的 prompt 让模型 {@code Use "Grep"}，本工具补上这个缺口，避免
 * {@code No ToolCallback found for tool name: Grep}。
 *
 * <p>One instance per chat request, bound to that request's sandbox.
 */
public class SandboxGrepTool {

	private static final int MAX_DEPTH = 100;

	private static final int MAX_OUTPUT_LENGTH = 100_000;

	private static final int MAX_LINE_LENGTH = 10_000;

	/** 沙箱 workDir 永远是 /work；输出路径统一加这个前缀，与 Read 工具的输入约定对齐可往返。 */
	private static final String SANDBOX_ROOT = "/work";

	private static final Map<String, String[]> FILE_TYPE_EXTENSIONS = new HashMap<>();
	static {
		FILE_TYPE_EXTENSIONS.put("java", new String[] { "*.java" });
		FILE_TYPE_EXTENSIONS.put("js", new String[] { "*.js", "*.jsx" });
		FILE_TYPE_EXTENSIONS.put("ts", new String[] { "*.ts", "*.tsx" });
		FILE_TYPE_EXTENSIONS.put("py", new String[] { "*.py" });
		FILE_TYPE_EXTENSIONS.put("rust", new String[] { "*.rs" });
		FILE_TYPE_EXTENSIONS.put("go", new String[] { "*.go" });
		FILE_TYPE_EXTENSIONS.put("cpp", new String[] { "*.cpp", "*.cc", "*.cxx", "*.hpp", "*.h" });
		FILE_TYPE_EXTENSIONS.put("c", new String[] { "*.c", "*.h" });
		FILE_TYPE_EXTENSIONS.put("rb", new String[] { "*.rb" });
		FILE_TYPE_EXTENSIONS.put("php", new String[] { "*.php" });
		FILE_TYPE_EXTENSIONS.put("cs", new String[] { "*.cs" });
		FILE_TYPE_EXTENSIONS.put("xml", new String[] { "*.xml" });
		FILE_TYPE_EXTENSIONS.put("json", new String[] { "*.json" });
		FILE_TYPE_EXTENSIONS.put("yaml", new String[] { "*.yaml", "*.yml" });
		FILE_TYPE_EXTENSIONS.put("md", new String[] { "*.md", "*.markdown" });
		FILE_TYPE_EXTENSIONS.put("txt", new String[] { "*.txt" });
		FILE_TYPE_EXTENSIONS.put("sh", new String[] { "*.sh", "*.bash" });
	}

	/**
	 * 输出模式。枚举常量名即 JSON 字符串值（{@code files_with_matches} / {@code count} / {@code content}），
	 * 与上游 {@code GrepTool.OutputMode} 一致。
	 */
	public enum OutputMode {
		files_with_matches, count, content
	}

	private final Sandbox sandbox;

	public SandboxGrepTool(Sandbox sandbox) {
		this.sandbox = sandbox;
	}

	// @formatter:off
	@Tool(name = "Grep", description = """
			A powerful search tool built with pure Java (no external dependencies required)

			Usage:
			- ALWAYS use Grep for search tasks. NEVER invoke `grep` or `rg` as a Bash command. The Grep tool has been optimized for correct permissions and access.
			- Supports full regex syntax (e.g., "log.*Error", "function\\s+\\w+")
			- Filter files with glob parameter (e.g., "*.js", "**/*.tsx") or type parameter (e.g., "js", "py", "rust")
			- Output modes: "content" shows matching lines, "files_with_matches" shows only file paths (default), "count" shows match counts
			- Use Task tool for open-ended searches requiring multiple rounds
			- Pattern syntax: Java regex - use standard Java regex escaping
			- Multiline matching: By default patterns match within single lines only. For cross-line patterns, use `multiline: true`

			Note: This is a pure Java implementation that doesn't require ripgrep installation. But it provides similar functionality.
			""")
	public String grep(
			@ToolParam(description = "The regular expression pattern to search for in file contents") String pattern,
			@ToolParam(description = "File or directory to search in. Defaults to current working directory.", required = false) String path,
			@ToolParam(description = "Glob pattern to filter files (e.g. \"*.js\", \"**/*.tsx\")", required = false) String glob,
			@ToolParam(description = "Output mode: \"content\" shows matching lines (supports -A/-B/-C context, -n line numbers, head_limit), \"files_with_matches\" shows file paths (supports head_limit), \"count\" shows match counts (supports head_limit). Defaults to \"files_with_matches\".", required = false) OutputMode outputMode,
			@ToolParam(description = "Number of lines to show before each match. Requires output_mode: \"content\", ignored otherwise.", required = false) Integer contextBefore,
			@ToolParam(description = "Number of lines to show after each match. Requires output_mode: \"content\", ignored otherwise.", required = false) Integer contextAfter,
			@ToolParam(description = "Number of lines to show before and after each match. Requires output_mode: \"content\", ignored otherwise.", required = false) Integer context,
			@ToolParam(description = "Show line numbers in output. Requires output_mode: \"content\", ignored otherwise. Defaults to true.", required = false) Boolean showLineNumbers,
			@ToolParam(description = "Case insensitive search", required = false) Boolean caseInsensitive,
			@ToolParam(description = "File type to search. Common types: js, py, rust, go, java, etc. More efficient than glob for standard file types.", required = false) String type,
			@ToolParam(description = "Limit output to first N lines/entries. Works across all output modes: content (limits output lines), files_with_matches (limits file paths), count (limits count entries). Defaults to 0 (unlimited).", required = false) Integer headLimit,
			@ToolParam(description = "Skip first N lines/entries before applying head_limit. Works across all output modes. Defaults to 0.", required = false) Integer offset,
			@ToolParam(description = "Enable multiline mode where . matches newlines and patterns can span lines. Default: false.", required = false) Boolean multiline) {
		// @formatter:on
		if (!StringUtils.hasText(pattern)) {
			return "Error: pattern must not be empty";
		}
		try {
			String rel = StringUtils.hasText(path) ? SandboxPaths.toRelative(path) : "";

			// Compile regex pattern.
			int flags = Pattern.MULTILINE;
			if (Boolean.TRUE.equals(caseInsensitive)) {
				flags |= Pattern.CASE_INSENSITIVE;
			}
			if (Boolean.TRUE.equals(multiline)) {
				flags |= Pattern.DOTALL;
			}
			Pattern searchPattern;
			try {
				searchPattern = Pattern.compile(pattern, flags);
			}
			catch (Exception e) {
				return "Error: Invalid regex pattern: " + e.getMessage();
			}

			// List files under the search root; map to workDir-relative paths.
			List<FileEntry> entries;
			try {
				entries = sandbox.files().list(rel, MAX_DEPTH);
			}
			catch (SandboxException e) {
				return "Error: Path does not exist: " + (StringUtils.hasText(path) ? path : SANDBOX_ROOT);
			}

			List<PathMatcher> matchers = buildGlobMatchers(glob, type);
			List<String> files = new ArrayList<>();
			for (FileEntry entry : entries) {
				if (!entry.isFile() || isIgnoredPath(entry.path())) {
					continue;
				}
				if (matchesGlob(entry.path(), matchers)) {
					files.add(entry.path());
				}
			}

			OutputMode mode = outputMode != null ? outputMode : OutputMode.files_with_matches;
			String result;
			switch (mode) {
				case count -> result = searchCount(files, searchPattern, headLimit, offset);
				case content -> {
					int beforeContext = context != null ? context : (contextBefore != null ? contextBefore : 0);
					int afterContext = context != null ? context : (contextAfter != null ? contextAfter : 0);
					boolean lineNumbers = showLineNumbers == null || showLineNumbers;
					result = searchContent(files, searchPattern, beforeContext, afterContext, lineNumbers, headLimit,
							offset);
				}
				default -> result = searchFilesWithMatches(files, searchPattern, headLimit, offset);
			}

			if (result.length() > MAX_OUTPUT_LENGTH) {
				result = result.substring(0, MAX_OUTPUT_LENGTH) + "\n... (output truncated, "
						+ (result.length() - MAX_OUTPUT_LENGTH) + " characters omitted)";
			}
			return result;
		}
		catch (Exception e) {
			return "Error executing grep: " + e.getMessage();
		}
	}

	private List<PathMatcher> buildGlobMatchers(String glob, String type) {
		List<PathMatcher> matchers = new ArrayList<>();
		if (StringUtils.hasText(type)) {
			String[] extensions = FILE_TYPE_EXTENSIONS.get(type.toLowerCase());
			if (extensions != null) {
				for (String ext : extensions) {
					matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + ext));
				}
			}
		}
		if (StringUtils.hasText(glob)) {
			String globPattern = glob.startsWith("**/") ? glob : "**/" + glob;
			matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + globPattern));
		}
		return matchers;
	}

	private boolean matchesGlob(String relativePath, List<PathMatcher> matchers) {
		if (matchers.isEmpty()) {
			return true;
		}
		// entry.path() 是 workDir 相对路径；顶层文件无目录分隔符，直接匹配会让带分隔符的 glob 落空。
		// 同时尝试 /work 前缀全路径（跨深度 glob）与 basename（type 扩展名 glob，如 *.java）。
		Path full = Path.of(SANDBOX_ROOT, relativePath);
		Path name = full.getFileName();
		for (PathMatcher matcher : matchers) {
			if (matcher.matches(full)) {
				return true;
			}
			if (name != null && matcher.matches(name)) {
				return true;
			}
		}
		return false;
	}

	private boolean isIgnoredPath(String pathStr) {
		String s = pathStr.replace('\\', '/');
		return s.contains("/.git/") || s.contains("/node_modules/") || s.contains("/target/")
				|| s.contains("/build/") || s.contains("/.idea/") || s.contains("/.vscode/")
				|| s.contains("/dist/") || s.contains("/__pycache__/") || s.startsWith(".git/");
	}

	private String readFileContent(String rel) {
		try {
			return sandbox.files().read(rel);
		}
		catch (SandboxException e) {
			return null; // skip unreadable files
		}
	}

	private String searchFilesWithMatches(List<String> files, Pattern pattern, Integer headLimit, Integer offset) {
		List<String> matchingFiles = new ArrayList<>();
		AtomicInteger count = new AtomicInteger(0);
		int skip = offset != null ? offset : 0;
		int limit = headLimit != null && headLimit > 0 ? headLimit : Integer.MAX_VALUE;

		for (String file : files) {
			if (count.get() >= skip + limit) {
				break;
			}
			String content = readFileContent(file);
			if (content == null) {
				continue;
			}
			if (fileContainsPattern(content, pattern)) {
				if (count.getAndIncrement() >= skip) {
					matchingFiles.add(SANDBOX_ROOT + "/" + file);
				}
			}
		}

		if (matchingFiles.isEmpty()) {
			return "No matches found for pattern: " + pattern.pattern();
		}
		return String.join("\n", matchingFiles);
	}

	private String searchCount(List<String> files, Pattern pattern, Integer headLimit, Integer offset) {
		Map<String, Integer> fileCounts = new LinkedHashMap<>();
		AtomicInteger fileCount = new AtomicInteger(0);
		int skip = offset != null ? offset : 0;
		int limit = headLimit != null && headLimit > 0 ? headLimit : Integer.MAX_VALUE;

		for (String file : files) {
			if (fileCount.get() >= skip + limit) {
				break;
			}
			String content = readFileContent(file);
			if (content == null) {
				continue;
			}
			int matches = countMatchesInFile(content, pattern);
			if (matches > 0) {
				if (fileCount.getAndIncrement() >= skip) {
					fileCounts.put(SANDBOX_ROOT + "/" + file, matches);
				}
			}
		}

		if (fileCounts.isEmpty()) {
			return "No matches found for pattern: " + pattern.pattern();
		}
		StringBuilder result = new StringBuilder();
		for (Map.Entry<String, Integer> entry : fileCounts.entrySet()) {
			result.append(entry.getKey()).append(":").append(entry.getValue()).append("\n");
		}
		return result.toString().trim();
	}

	private String searchContent(List<String> files, Pattern pattern, int beforeContext, int afterContext,
			boolean lineNumbers, Integer headLimit, Integer offset) {
		StringBuilder result = new StringBuilder();
		AtomicInteger lineCount = new AtomicInteger(0);
		int skip = offset != null ? offset : 0;
		int limit = headLimit != null && headLimit > 0 ? headLimit : Integer.MAX_VALUE;

		for (String file : files) {
			if (lineCount.get() >= skip + limit) {
				break;
			}
			String content = readFileContent(file);
			if (content == null) {
				continue;
			}
			List<String> matches = findMatchesWithContext(content, pattern, beforeContext, afterContext, lineNumbers);
			if (matches.isEmpty()) {
				continue;
			}
			result.append(SANDBOX_ROOT).append("/").append(file).append("\n");
			for (String match : matches) {
				if (lineCount.get() >= skip + limit) {
					break;
				}
				if (lineCount.getAndIncrement() >= skip) {
					result.append(match).append("\n");
				}
			}
			result.append("\n");
		}

		if (result.length() == 0) {
			return "No matches found for pattern: " + pattern.pattern();
		}
		return result.toString().trim();
	}

	private static boolean fileContainsPattern(String content, Pattern pattern) {
		return content.lines().anyMatch(line -> line.length() <= MAX_LINE_LENGTH && pattern.matcher(line).find());
	}

	private static int countMatchesInFile(String content, Pattern pattern) {
		int count = 0;
		for (String line : content.lines().toList()) {
			if (line.length() > MAX_LINE_LENGTH) {
				continue;
			}
			Matcher matcher = pattern.matcher(line);
			while (matcher.find()) {
				count++;
			}
		}
		return count;
	}

	private static List<String> findMatchesWithContext(String content, Pattern pattern, int beforeContext,
			int afterContext, boolean lineNumbers) {
		List<String> results = new ArrayList<>();
		List<String> allLines = content.lines().toList();
		List<Integer> matchingLineNumbers = new ArrayList<>();

		for (int i = 0; i < allLines.size(); i++) {
			String line = allLines.get(i);
			if (line.length() > MAX_LINE_LENGTH) {
				continue;
			}
			if (pattern.matcher(line).find()) {
				matchingLineNumbers.add(i);
			}
		}

		for (int matchLineNum : matchingLineNumbers) {
			int start = Math.max(0, matchLineNum - beforeContext);
			int end = Math.min(allLines.size() - 1, matchLineNum + afterContext);
			for (int i = start; i <= end; i++) {
				String prefix = "";
				if (lineNumbers) {
					prefix = (i + 1) + ":";
				}
				prefix += (i == matchLineNum) ? "  " : "- ";
				results.add(prefix + allLines.get(i));
			}
			results.add("--");
		}

		if (!results.isEmpty() && "--".equals(results.get(results.size() - 1))) {
			results.remove(results.size() - 1);
		}
		return results;
	}

}
