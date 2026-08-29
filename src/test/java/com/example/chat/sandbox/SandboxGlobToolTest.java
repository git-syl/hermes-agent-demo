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
 * {@link SandboxGlobTool} 单测：mock {@link Sandbox} + {@link SandboxFiles}，stub {@code list(...)}
 * 返回 canned {@link FileEntry} 列表，验证 (1) 只取文件、排除目录与忽略路径、(2) 按 mtime 降序、
 * (3) glob 过滤、(4) 空结果文案、(5) 搜索路径不存在时返回 error 文案。
 *
 * <p>建立了 {@code mock(SandboxFiles.class)} 先例（项目里此前无 Sandbox 工具的单测）。
 */
class SandboxGlobToolTest {

	@Test
	void globReturnsFilesSortedByMtimeDescExcludingDirsAndIgnored() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files);
		when(files.list(anyString(), anyInt())).thenReturn(List.of(
				entry("a.java", FileType.FILE, 1_000L),
				entry("b.py", FileType.FILE, 3_000L),
				entry("c.md", FileType.FILE, 2_000L),
				entry("sub", FileType.DIRECTORY, 5_000L), // 目录，排除
				entry(".git/config", FileType.FILE, 9_000L))); // 忽略路径，排除

		String result = new SandboxGlobTool(sandbox).glob("**/*", null);

		// mtime 降序：b.py(3000) > c.md(2000) > a.java(1000)；目录与 .git/ 被排除。
		assertThat(result).isEqualTo("/work/b.py\n/work/c.md\n/work/a.java");
	}

	@Test
	void globFiltersByPattern() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files);
		when(files.list(anyString(), anyInt())).thenReturn(List.of(
				entry("a.java", FileType.FILE, 1_000L),
				entry("b.py", FileType.FILE, 3_000L),
				entry("c.md", FileType.FILE, 2_000L)));

		String result = new SandboxGlobTool(sandbox).glob("*.py", null);

		assertThat(result).isEqualTo("/work/b.py");
	}

	@Test
	void globReturnsNoFilesMessageWhenEmpty() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files);
		when(files.list(anyString(), anyInt())).thenReturn(List.of());

		String result = new SandboxGlobTool(sandbox).glob("**/*", null);

		assertThat(result).isEqualTo("No files found matching pattern: **/*");
	}

	@Test
	void globReturnsErrorWhenPathMissing() {
		SandboxFiles files = mock(SandboxFiles.class);
		Sandbox sandbox = stubFiles(files);
		when(files.list(anyString(), anyInt())).thenThrow(new SandboxException("Path does not exist: nope"));

		String result = new SandboxGlobTool(sandbox).glob("*.py", "/work/nope");

		assertThat(result).isEqualTo("Error: Path does not exist: /work/nope");
	}

	// -------- helpers --------

	private static Sandbox stubFiles(SandboxFiles files) {
		Sandbox sandbox = mock(Sandbox.class);
		when(sandbox.files()).thenReturn(files);
		return sandbox;
	}

	private static FileEntry entry(String path, FileType type, long mtimeMillis) {
		String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
		return new FileEntry(name, type, path, 10L, Instant.ofEpochMilli(mtimeMillis));
	}

}
