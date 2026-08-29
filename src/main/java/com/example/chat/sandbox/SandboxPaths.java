package com.example.chat.sandbox;

import java.nio.file.Path;

/**
 * 把模型传入的路径归一化为沙箱 workDir 相对路径。绝不放任它逃出沙箱：盘符、绝对前导
 * {@code /}、{@code ..} 遍历段一律剥掉；沙箱 API 自身在执行层再 jail 一次。
 *
 * <p>共享自 {@link SandboxFileSystemTools}（Read/Write/Edit）、{@link SandboxGrepTool}、
 * {@link SandboxGlobTool} 三个调用方——按代码库「第 3 个调用方出现就收敛」规则抽出。
 *
 * <p>沙箱 workDir 永远是 {@code /work}。模型按 prompt 规则把 host path 翻译成 {@code "/work/X"}
 * 后传进来，剥前导 {@code /} 之后变成 {@code "work/X"}；若不剥这层，{@code sandbox.files()} 会
 * 再拼一次 {@code /work/} 导致路径漂移到 {@code /work/work/X}。故统一把 {@code "work/"} 前缀视为
 * workDir 锚点剥掉。
 *
 * @throws IllegalArgumentException 若 {@code filePath} 为 null 或空白
 */
public final class SandboxPaths {

	private SandboxPaths() {
	}

	public static String toRelative(String filePath) {
		if (filePath == null || filePath.isBlank()) {
			throw new IllegalArgumentException("filePath must not be empty");
		}
		String normalized = filePath.replace('\\', '/').trim();
		// Strip Windows drive letters and leading slashes.
		if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
			normalized = normalized.substring(2);
		}
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		if (normalized.equals("work")) {
			normalized = "";
		}
		else if (normalized.startsWith("work/")) {
			normalized = normalized.substring("work/".length());
		}
		Path p = Path.of(normalized).normalize();
		// Strip any leading ".." after normalization.
		while (p.getNameCount() > 0 && "..".equals(p.getName(0).toString())) {
			p = p.subpath(1, p.getNameCount());
		}
		return p.toString().replace('\\', '/');
	}

}
