package com.example.chat.service;

import com.example.chat.api.dto.ChatRequest.McpServerConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds MCP clients per request from a {@code mcpConfig} map. Each session must be
 * closed (try-with-resources) so network connections and stdio processes don't leak.
 */
@Component
public class DynamicMcpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(DynamicMcpClientFactory.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    public McpClients build(Map<String, McpServerConfig> mcpConfig) {
        List<McpSyncClient> clients = new ArrayList<>();
        if (mcpConfig == null || mcpConfig.isEmpty()) {
            return new McpClients(clients);
        }
        try {
            for (var entry : mcpConfig.entrySet()) {
                McpSyncClient client = buildClient(entry.getKey(), entry.getValue());
                if (client != null) {
                    clients.add(client);
                }
            }
        } catch (RuntimeException e) {
            // partial failure: close anything we already opened before re-throwing
            closeQuietly(clients);
            throw e;
        }
        return new McpClients(clients);
    }

    private McpSyncClient buildClient(String name, McpServerConfig cfg) {
        if (cfg == null) {
            return null;
        }
        McpClientTransport transport;
        if (cfg.url() != null && !cfg.url().isBlank()) {
            // Split the user-provided URL into (scheme://host[:port]) + (path?query).
            // The MCP SDK internally does URI.resolve(baseUri, endpoint). If endpoint
            // starts with '/' (absolute path) the baseUri's path and query are
            // discarded — that strips out things like '?key=xxx'. By passing only the
            // origin as baseUri and the rest as a relative endpoint, the original
            // query string is preserved on every POST.
            URI full = URI.create(cfg.url());
            String origin = full.getScheme() + "://" + full.getRawAuthority();
            String rawPath = full.getRawPath() == null || full.getRawPath().isEmpty() ? "/mcp" : full.getRawPath();
            // Make endpoint relative (drop the leading '/') so URI.resolve keeps the
            // current behavior intuitive and preserves any query string we append.
            String relativeEndpoint = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
            if (full.getRawQuery() != null && !full.getRawQuery().isEmpty()) {
                relativeEndpoint = relativeEndpoint + "?" + full.getRawQuery();
            }

            HttpClientStreamableHttpTransport.Builder builder =
                    HttpClientStreamableHttpTransport.builder(origin)
                            .endpoint(relativeEndpoint);
            Map<String, String> headers = cfg.headers();
            if (headers != null && !headers.isEmpty()) {
                builder.httpRequestCustomizer((req, method, endpoint, body, context) ->
                        headers.forEach(req::header));
            }
            transport = builder.build();
        } else {
            log.warn("Skipping MCP entry '{}': missing 'url' (only streamable-http / SSE transports are supported)", name);
            return null;
        }
        McpSyncClient client = McpClient.sync(transport).requestTimeout(REQUEST_TIMEOUT).build();
        client.initialize();
        log.info("MCP client initialized: {}", name);
        return client;
    }

    private static void closeQuietly(List<McpSyncClient> clients) {
        for (McpSyncClient c : clients) {
            try {
                c.closeGracefully();
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    /**
     * Holds MCP clients opened for one request and exposes the merged tool callbacks.
     */
    public static final class McpClients implements AutoCloseable {

        private final List<McpSyncClient> clients;

        McpClients(List<McpSyncClient> clients) {
            this.clients = clients;
        }

        public ToolCallback[] getToolCallbacks() {
            if (clients.isEmpty()) {
                return new ToolCallback[0];
            }
            return SyncMcpToolCallbackProvider.builder()
                    .mcpClients(clients)
                    .build()
                    .getToolCallbacks();
        }

        @Override
        public void close() {
            closeQuietly(clients);
        }
    }
}
