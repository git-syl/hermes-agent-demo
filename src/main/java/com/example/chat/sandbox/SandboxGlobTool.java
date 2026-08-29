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
import java.util.Comparator;
import java.util.List;

/**
 * 沙箱版 {@code Glob} —— 按文件名 glob 模式找文件，返回按修改时间降序的路径列表。
 *
 * <p>工具描述、参数签名、返回值文案严格对齐上游 {@link org.springaicommunity.agent.tools.GlobTool}，
 * 唯一差异：上游用宿主机 {@code Files.walk}，本类走 {@link Sandbox#files()} 的非 shell 文件 API
 * —— 路径相对沙箱 workDir，{@link SandboxPaths#toRelative} 还会剥 {@code ..}，故即使 LOCAL 模式
 * 也只读沙箱 tempdir，不碰任意宿主路径。内置 Claude 子代理（Explore/Plan）的 prompt 让模型
 * {@code Use "Glob"}，本工具补上这个缺口，避免 {@code No ToolCallback found for tool name: Glob}。
 *
 * <p>One instance per chat request, bound to that request's sandbox.
 */
public class SandboxGlobTool {

	private static final int MAX_DEPTH = 100;

	private static final int MAX_RESULTS = 1000;

	/** 沙箱 workDir 永远是 /work；输出路径统一加这个前缀，与 Read 工具的输入约定对齐可往返。 */
	private static final String SANDBOX_ROOT = "/work";

	private final Sandbox sandbox;

	public SandboxGlobTool(Sandbox sandbox) {
		this.sandbox = sandbox;
	}

	// @formatter:off
	@Tool(name = "Glob", description = """
			- Fast file pattern matching tool that works with any codebase size
			- Supports glob patterns like "**/*.js" or "src/**/*.ts"
			- Returns matching file paths sorted by modification time
			- Use this tool when you need to find files by name patterns
			- When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the Agent tool instead
			- You can call multiple tools in a single response. It is always better to speculatively perform multiple searches in parallel if they are potentially useful.
			""")
	public String glob(
			@ToolParam(description = "The glob pattern to match files against") String pattern,
			@ToolParam(description = "The directory to search in. If not specified, the current working directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \\\"undefined\\\" or \\\"null\\\" - simply omit it for the default behavior. Must be a valid directory path if provided.", required = false) String path) {
		// @formatter:on
		if (!StringUtils.hasText(pattern)) {
			return "Error: The glob pattern must not be empty";
		}
		try {
			String rel = StringUtils.hasText(path) ? SandboxPaths.toRelative(path) : "";
			List<FileEntry> entries;
			try {
				entries = sandbox.files().list(rel, MAX_DEPTH);
			}
			catch (SandboxException e) {
				return "Error: Path does not exist: " + (StringUtils.hasText(path) ? path : SANDBOX_ROOT);
			}

			PathMatcher matcher = buildGlobMatcher(pattern);
			List<FileEntry> matching = new ArrayList<>();
			for (FileEntry entry : entries) {
				if (!entry.isFile() || isIgnoredPath(entry.path())) {
					continue;
				}
				if (!matchesPattern(entry.path(), matcher)) {
					continue;
				}
				matching.add(entry);
				if (matching.size() >= MAX_RESULTS) {
					break;
				}
			}

			if (matching.isEmpty()) {
				return "No files found matching pattern: " + pattern;
			}

			// Sort by modification time, most recent first.
			matching.sort(Comparator.comparing(FileEntry::modifiedTime).reversed());

			StringBuilder result = new StringBuilder();
			for (FileEntry entry : matching) {
				result.append(SANDBOX_ROOT).append('/').append(entry.path()).append('\n');
			}
			return result.toString().trim();
		}
		catch (Exception e) {
			return "Error executing glob: " + e.getMessage();
		}
	}

	/** 模式若不以跨深度前缀开头则自动补上，让 {@code *.py} 这类简单 glob 也能命中任意深度的文件。 */
	private static PathMatcher buildGlobMatcher(String pattern) {
		String globPattern = pattern.startsWith("**/") ? pattern : "**/" + pattern;
		return FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
	}

	/**
	 * {@code FileEntry.path()} 恒为 workDir 相对路径（docker 实现剥 {@code /work/}，local 实现
	 * {@code workDir.relativize}），故直接拿它匹配即可；跨深度前缀保证任意深度命中。
	 */
	private static boolean matchesPattern(String relativePath, PathMatcher matcher) {
		// entry.path() 是 workDir 相对路径；顶层文件无目录分隔符，直接匹配会让带分隔符的 glob 落空。
		// 同时尝试 /work 前缀全路径（跨深度 glob）与 basename（type 扩展名 glob，如 *.java）。
		try {
			Path full = Path.of(SANDBOX_ROOT, relativePath);
			if (matcher.matches(full)) {
				return true;
			}
			Path name = full.getFileName();
			return name != null && matcher.matches(name);
		}
		catch (Exception e) {
			return false;
		}
	}

	private static boolean isIgnoredPath(String pathStr) {
		String s = pathStr.replace('\\', '/');
		return s.contains("/.git/") || s.contains("/node_modules/") || s.contains("/target/")
				|| s.contains("/build/") || s.contains("/.idea/") || s.contains("/.vscode/")
				|| s.contains("/dist/") || s.contains("/__pycache__/") || s.startsWith(".git/");
	}

}
