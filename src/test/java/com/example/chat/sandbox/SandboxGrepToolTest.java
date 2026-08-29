package com.example.chat.sandbox;

import io.github.markpollack.sandbox.FileEntry;
import io.github.markpollack.sandbox.FileType;
import io.github.markpollack.sandbox.Sandbox;
import io.github.markpollack.sandbox.SandboxException;
import io.github.markpollack.sandbox.SandboxFiles;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SandboxGrepTool} 单测：mock {@link Sandbox} + {@link SandboxFiles}，stub {@code list(...)}
 * 返回 canned 文件列表、{@code read(...)} 返回 canned 内容，验证三种输出模式、大小写、glob 过滤、
 * 上下文行、空结果文案、非法正则、搜索路径不存在的 error 文案。
 *
 * <p>直接调 {@code tool.grep(...)}（不走 ToolCallback JSON 派发），覆盖工具自身逻辑。
 */
class SandboxGrepToolTest {

	@Test
	void filesWithMatchesModeReturnsMatchingFilePaths() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files, List.of(file("a.py"), file("b.py"), file("c.txt")));
		when(files.read("a.py")).thenReturn("import disallowedTools\n");
		when(files.read("b.py")).thenReturn("nothing here\n");
		when(files.read("c.txt")).thenReturn("disallowedTools mentioned\n");

		String result = grep(sandbox, "disallowedTools", SandboxGrepTool.OutputMode.files_with_matches);

		assertThat(result).isEqualTo("/work/a.py\n/work/c.txt");
	}

	@Test
	void countModeReturnsPerFileCounts() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files, List.of(file("a.py"), file("b.py")));
		when(files.read("a.py")).thenReturn("disallowedTools here\ndisallowedTools again\n");
		when(files.read("b.py")).thenReturn("disallowedTools once\n");

		String result = grep(sandbox, "disallowedTools", SandboxGrepTool.OutputMode.count);

		assertThat(result).isEqualTo("/work/a.py:2\n/work/b.py:1");
	}

	@Test
	void contentModeShowsLineNumbersAndContext() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files, List.of(file("a.py")));
		when(files.read("a.py")).thenReturn("line1\ndisallowedTools here\nline3");

		SandboxGrepTool tool = new SandboxGrepTool(sandbox);
		// context=1（覆盖 contextBefore/contextAfter），showLineNumbers 默认 true。
		String result = tool.grep("disallowedTools", null, null, SandboxGrepTool.OutputMode.content,
				null, null, 1, null, null, null, null, null, null);

		// 匹配行用 "  " 后缀，上下文行用 "- " 后缀；行号从 1 起。
		assertThat(result).isEqualTo("/work/a.py\n1:- line1\n2:  disallowedTools here\n3:- line3");
	}

	@Test
	void caseInsensitiveMatches() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files, List.of(file("a.py")));
		when(files.read("a.py")).thenReturn("DisallowedTools here\n");

		SandboxGrepTool tool = new SandboxGrepTool(sandbox);
		String result = tool.grep("disallowedtools", null, null, SandboxGrepTool.OutputMode.files_with_matches,
				null, null, null, null, true, null, null, null, null);

		assertThat(result).isEqualTo("/work/a.py");
	}

	@Test
	void globFilterRestrictsSearchedFiles() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files, List.of(file("a.py"), file("b.txt")));
		when(files.read("a.py")).thenReturn("disallowedTools\n");
		when(files.read("b.txt")).thenReturn("disallowedTools\n");

		SandboxGrepTool tool = new SandboxGrepTool(sandbox);
		// glob="*.py" → 只搜 .py 文件，b.txt 被过滤掉。
		String result = tool.grep("disallowedTools", null, "*.py",
				SandboxGrepTool.OutputMode.files_with_matches, null, null, null, null, null, null, null, null,
				null);

		assertThat(result).isEqualTo("/work/a.py");
	}

	@Test
	void noMatchesReturnsEmptyMessage() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files, List.of(file("a.py")));
		when(files.read("a.py")).thenReturn("nothing relevant\n");

		String result = grep(sandbox, "disallowedTools", SandboxGrepTool.OutputMode.files_with_matches);

		assertThat(result).isEqualTo("No matches found for pattern: disallowedTools");
	}

	@Test
	void invalidRegexReturnsErrorMessage() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files, List.of(file("a.py")));

		String result = grep(sandbox, "[", SandboxGrepTool.OutputMode.files_with_matches);

		assertThat(result).startsWith("Error: Invalid regex pattern:");
	}

	@Test
	void pathMissingReturnsError() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files, List.of());
		when(files.list(anyString(), anyInt())).thenThrow(new SandboxException("Path does not exist: nope"));

		SandboxGrepTool tool = new SandboxGrepTool(sandbox);
		String result = tool.grep("x", "/work/nope", null, SandboxGrepTool.OutputMode.files_with_matches, null,
				null, null, null, null, null, null, null, null);

		assertThat(result).isEqualTo("Error: Path does not exist: /work/nope");
	}

	// -------- helpers --------

	private static String grep(Sandbox sandbox, String pattern, SandboxGrepTool.OutputMode mode) {
		return new SandboxGrepTool(sandbox).grep(pattern, null, null, mode, null, null, null, null, null, null,
				null, null, null);
	}

	private static Sandbox stubFiles(SandboxFiles files, List<FileEntry> entries) {
		Sandbox sandbox = mock(Sandbox.class);
		when(sandbox.files()).thenReturn(files);
		when(files.list(anyString(), anyInt())).thenReturn(entries);
		return sandbox;
	}

	private static FileEntry file(String path) {
		return new FileEntry(path, FileType.FILE, path, 10L, Instant.ofEpochMilli(1000L));
	}

}
