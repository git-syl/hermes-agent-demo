package com.example.chat.artifact;

/**
 * Handle to an exported artifact. Returned by {@code SandboxArtifactTool} to
 * the LLM so it can include the URL in its reply.
 *
 * @param id        opaque artifact id (used in the download URL path)
 * @param filename  original filename inside the sandbox (e.g. {@code report.pdf})
 * @param mimeType  best-effort content type (text/plain if unknown)
 * @param size      file size in bytes
 * @param url       publicly accessible download URL
 */
public record ArtifactRef(String id, String filename, String mimeType, long size, String url) {
}
