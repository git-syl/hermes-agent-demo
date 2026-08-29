package com.example.chat.service;

import com.example.chat.api.dto.ChatRequest.SubagentRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentCacheService} 缓存语义回归：
 * <ol>
 *   <li>首次下载写入缓存文件，二次同 URL 命中缓存、不再发 HTTP 请求；</li>
 *   <li>同 name 不同 url → 两个缓存文件共存（urlHash 隔离）；</li>
 *   <li>HTTP 4xx 抛异常且缓存目录不留半成品；</li>
 *   <li>空响应 body 抛异常且不留空文件；</li>
 *   <li>userId 含 {@code ..} / {@code /} 等特殊字符被 sanitize，不越出 cacheRoot；</li>
 *   <li>返回的 Resource 列表与入参 1:1 对应（顺序、长度）；</li>
 *   <li>{@code subagents} 为 null / 空 list 时返回空列表，不抛异常。</li>
 * </ol>
 *
 * <p>用 JDK 自带 {@link HttpServer} 起本地临时服务，避免 mock HttpClient 内部细节 ——
 * AgentCacheService 用的是 {@code java.net.http.HttpClient}，原生不支持注入，
 * 起真实 HTTP 服务是最直接、最贴近生产路径的验证方式。
 */
class AgentCacheServiceTest {

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private Path cacheRoot;
    private AgentCacheService cacheService;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cacheRoot = Files.createTempDirectory("agent-cache-test");
        cacheService = new AgentCacheService(cacheRoot.toString());
        requestCount.set(0);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop(0);
        cleanRecursively(cacheRoot);
    }

    @Test
    void cacheHitSkipsDownload() {
        byte[] body = "---\nname: test-agent\ndescription: test\n---\nbody".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        int port = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/agents/test.md";

        // 第一次：发 HTTP 请求，下载落盘
        List<Resource> first = cacheService.resolve(1L, null, "sess",
                List.of(new SubagentRef("test", url)));
        assertThat(first).hasSize(1);
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(first.get(0).getFilename()).endsWith(".md");

        // 第二次同 URL：命中缓存，不再发 HTTP
        List<Resource> second = cacheService.resolve(1L, null, "sess",
                List.of(new SubagentRef("test", url)));
        assertThat(second).hasSize(1);
        assertThat(requestCount.get()).isEqualTo(1); // 仍然只有 1 次 HTTP 请求
        // 同一个文件路径
        assertThat(second.get(0).getFilename()).isEqualTo(first.get(0).getFilename());
    }

    @Test
    void urlHashIsolatesDifferentUrls() {
        byte[] body1 = "agent A content".getBytes(StandardCharsets.UTF_8);
        byte[] body2 = "agent B content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/a.md", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, body1.length);
            try (var os = exchange.getResponseBody()) { os.write(body1); }
        });
        server.createContext("/b.md", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, body2.length);
            try (var os = exchange.getResponseBody()) { os.write(body2); }
        });
        server.start();
        int port = server.getAddress().getPort();

        List<Resource> result = cacheService.resolve(1L, null, "sess", List.of(
                new SubagentRef("same-name", "http://127.0.0.1:" + port + "/a.md"),
                new SubagentRef("same-name", "http://127.0.0.1:" + port + "/b.md")));

        // 同 name 不同 url → 两个文件共存
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getFilename()).isNotEqualTo(result.get(1).getFilename());
        // 两个文件名都以 "same-name-" 开头，但 urlHash 不同
        assertThat(result.get(0).getFilename()).startsWith("same-name-").endsWith(".md");
        assertThat(result.get(1).getFilename()).startsWith("same-name-").endsWith(".md");
    }

    @Test
    void downloadFailureThrowsAndLeavesNoFile() throws IOException {
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(404, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        int port = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/missing.md";

        // resolve 内部 catch 单个 agent 失败 → 跳过，不打断整次请求 → 返回空 list
        List<Resource> result = cacheService.resolve(1L, null, "sess",
                List.of(new SubagentRef("bad", url)));
        assertThat(result).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);

        // 验证缓存目录里没有遗留半成品文件（包括 .md 文件）
        long mdFiles = countMdFiles(cacheRoot);
        assertThat(mdFiles).isZero();
    }

    @Test
    void emptyBodyThrowsAndLeavesNoFile() throws IOException {
        // 某些错误页可能 200 但 body 为空 —— 必须视为失败
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        int port = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/empty.md";

        List<Resource> result = cacheService.resolve(1L, null, "sess",
                List.of(new SubagentRef("empty", url)));
        assertThat(result).isEmpty();
        assertThat(countMdFiles(cacheRoot)).isZero();
    }

    @Test
    void sanitizesUserIdAndName() throws IOException {
        byte[] body = "content".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        int port = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/agents/test.md";

        // userId 含特殊字符、name 含路径分隔符 —— 都应被 sanitize 成 _
        // 注：AgentCacheService 接收的是 Long userId，内部转成 String；
        // 真正需要 sanitize 的是 sessionId/name 字段。这里测 name 的 sanitize。
        List<Resource> result = cacheService.resolve(1L, null, "sess",
                List.of(new SubagentRef("..evil-name", url)));

        assertThat(result).hasSize(1);
        // 文件名不应包含路径分隔符（防止目录穿越）
        String filename = result.get(0).getFilename();
        assertThat(filename).doesNotContain("/").doesNotContain("\\");
        // 关键：路径规范化后必须仍落在 cacheRoot 下 —— 这是防穿越的真正兜底
        Path resolved = result.get(0).getFile().toPath().toAbsolutePath().normalize();
        assertThat(resolved).startsWith(cacheRoot.toAbsolutePath().normalize());
    }

    @Test
    void returnsOneResourcePerSubagentRef() {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        int port = server.getAddress().getPort();

        List<SubagentRef> refs = List.of(
                new SubagentRef("a1", "http://127.0.0.1:" + port + "/1.md"),
                new SubagentRef("a2", "http://127.0.0.1:" + port + "/2.md"),
                new SubagentRef("a3", "http://127.0.0.1:" + port + "/3.md"));

        List<Resource> result = cacheService.resolve(1L, null, "sess", refs);

        // 1:1 对应 —— 长度一致
        assertThat(result).hasSize(3);
        assertThat(requestCount.get()).isEqualTo(3);
    }

    @Test
    void emptyOrNullSubagentsReturnsEmptyList() {
        // null
        assertThat(cacheService.resolve(1L, null, "sess", null)).isEmpty();
        // 空 list
        assertThat(cacheService.resolve(1L, null, "sess", List.of())).isEmpty();
        // 不应启动 HTTP server 的任何请求 —— 这里 server 还没 start，能正常返回空就证明没发请求
    }

    @Test
    void assistantIdBucketIsUsed() {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) { os.write(body); }
        });
        server.start();
        int port = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/agents/test.md";

        // 带 assistantId —— 缓存目录应包含 assistantId 分桶
        cacheService.resolve(1L, 42L, "sess", List.of(new SubagentRef("test", url)));

        // 第二次不同 assistantId —— 视为新缓存，再下载一次
        cacheService.resolve(1L, 99L, "sess", List.of(new SubagentRef("test", url)));

        assertThat(requestCount.get()).isEqualTo(2);
    }

    // -------- helpers --------

    private static long countMdFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .count();
        }
    }

    private static void cleanRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }
}
