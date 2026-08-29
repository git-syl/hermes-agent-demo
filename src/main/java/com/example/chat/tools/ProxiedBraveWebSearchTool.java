package com.example.chat.tools;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@code org.springaicommunity.agent.tools.BraveWebSearchTool} 的"可换 base-url"
 * 等价实现 —— 用于支持反代 / 镜像 / 出海代理的部署场景。
 *
 * <p><b>背景</b>：上游 {@code BraveWebSearchTool} 把
 * {@code https://api.search.brave.com} 写死成 {@code private static final}，
 * 在国内无法直连且无法通过 builder 修改。本类把 base-url 提升为 builder 参数，
 * 其它行为（路径、Header、域名过滤逻辑、JSON 输出格式）与原版逐行对齐。
 *
 * <p><b>工具签名</b>：保持 {@code @Tool(name = "WebSearch")} 与原版一致。
 * 因此与原版 {@code BraveWebSearchTool} <b>不能同时</b>注册到同一个 ChatClient，
 * 否则 {@code MethodToolCallbackProvider} 会按重名报错。当前项目走
 * {@code BraveSearchConfig}，已经只注册本类，不会冲突。
 *
 * <p>使用 {@code site:} 算子比客户端 allowed/blockedDomains 更省 API 配额，
 * 这点和原版完全一致。
 */
public class ProxiedBraveWebSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(ProxiedBraveWebSearchTool.class);

    private static final String WEB_SEARCH_PATH = "/res/v1/web/search";

    private final RestClient restClient;
    private final int resultCount;

    private ProxiedBraveWebSearchTool(String apiKey, String baseUrl, int resultCount) {
        Assert.hasText(apiKey, "API key must not be null or empty");
        Assert.hasText(baseUrl, "baseUrl must not be null or empty");
        this.restClient = RestClient.builder()
                .baseUrl(stripTrailingSlash(baseUrl))
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept-Encoding", "gzip")
                .defaultHeader("X-Subscription-Token", apiKey)
                .build();
        this.resultCount = resultCount;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // @formatter:off
    @Tool(name = "WebSearch", description = """
        - Allows the assistant to search the web and use the results to inform responses
        - Provides up-to-date information for current events and recent data
        - Returns search result information formatted as search result blocks, including links as markdown hyperlinks
        - Use this tool for accessing information beyond the model's knowledge cutoff
        - Searches are performed automatically within a single API call

        CRITICAL REQUIREMENT - You MUST follow this:
        - After answering the user's question, you MUST include a "Sources:" section at the end of your response
        - In the Sources section, list all relevant URLs from the search results as markdown hyperlinks: [Title](URL)
        - This is MANDATORY - never skip including sources in your response
        - Example format:

            [Your answer here]

            Sources:
            - [Source Title 1](https://example.com/1)
            - [Source Title 2](https://example.com/2)

        Usage notes:
        - Domain filtering is supported to include or block specific websites (applied client-side after fetching results)
        - For better API quota usage, consider using search operators in your query (e.g., "Spring AI site:spring.io")

        IMPORTANT - Use the correct year in search queries:
        - When searching for recent information, documentation, or current events, always include the current year in your query
        - Example: If searching for latest React docs, search for "React documentation 2025" rather than older years
        """)
    @SuppressWarnings("unchecked")
    public String webSearch(
            @ToolParam(description = "The search query to use") String query,
            @ToolParam(description = "Only include search results from these domains", required = false) List<String> allowedDomains,
            @ToolParam(description = "Never include search results from these domains", required = false) List<String> blockedDomains) {
        // @formatter:on

        if (!StringUtils.hasText(query)) {
            logger.warn("Empty search query provided");
            return JsonParser.toJson(Collections.emptyList());
        }

        try {
            if (!CollectionUtils.isEmpty(allowedDomains) || !CollectionUtils.isEmpty(blockedDomains)) {
                logger.debug("Client-side domain filtering will be applied. allowed={}, blocked={}",
                        allowedDomains, blockedDomains);
            }

            Map<String, Object> queryResponse = executeSearch(query);
            if (queryResponse == null || queryResponse.isEmpty()) {
                logger.warn("Empty response from Brave Search API for query: {}", query);
                return JsonParser.toJson(Collections.emptyList());
            }

            List<SearchResult> allResults = new ArrayList<>();
            if (queryResponse.containsKey("web")) {
                allResults.addAll(parseResults((Map<String, Object>) queryResponse.get("web")));
            }
            if (queryResponse.containsKey("videos")) {
                allResults.addAll(parseResults((Map<String, Object>) queryResponse.get("videos")));
            }

            List<SearchResult> filtered = applyDomainFiltering(allResults, allowedDomains, blockedDomains);
            if (filtered.size() < allResults.size()) {
                int removed = allResults.size() - filtered.size();
                logger.info("Search '{}' returned {} results, {} filtered out, {} remaining",
                        query, allResults.size(), removed, filtered.size());
            } else {
                logger.debug("Search '{}' returned {} results (no filtering)", query, allResults.size());
            }
            return JsonParser.toJson(filtered);
        } catch (RestClientException e) {
            logger.error("Error executing Brave Search request for query: {}", query, e);
            return JsonParser.toJson(Collections.emptyList());
        }
    }

    private Map<String, Object> executeSearch(String query) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(b -> b.path(WEB_SEARCH_PATH)
                            .queryParam("q", query)
                            .queryParam("count", resultCount)
                            .build())
                    .retrieve()
                    .onStatus(s -> s.is4xxClientError(), (req, resp) ->
                            logger.error("Client error from Brave API: {} for query: {}", resp.getStatusCode(), query))
                    .onStatus(s -> s.is5xxServerError(), (req, resp) ->
                            logger.error("Server error from Brave API: {} for query: {}", resp.getStatusCode(), query))
                    .body(Map.class);
            return response != null ? response : Collections.emptyMap();
        } catch (Exception e) {
            logger.error("Failed to execute search request for query: {}", query, e);
            return Collections.emptyMap();
        }
    }

    public record SearchResult(String title, String url, String description) {}

    @SuppressWarnings("unchecked")
    private List<SearchResult> parseResults(Map<String, Object> section) {
        if (CollectionUtils.isEmpty(section)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> results = (List<Map<String, Object>>) section.get("results");
        if (CollectionUtils.isEmpty(results)) {
            return Collections.emptyList();
        }
        return results.stream()
                .filter(e -> e != null && e.get("title") != null && e.get("url") != null)
                .map(e -> new SearchResult(
                        (String) e.get("title"),
                        (String) e.get("url"),
                        e.get("description") != null ? (String) e.get("description") : ""))
                .toList();
    }

    private List<SearchResult> applyDomainFiltering(List<SearchResult> results,
                                                    List<String> allowedDomains,
                                                    List<String> blockedDomains) {
        if (CollectionUtils.isEmpty(allowedDomains) && CollectionUtils.isEmpty(blockedDomains)) {
            return results;
        }
        Set<String> allowed = toLowerSet(allowedDomains);
        Set<String> blocked = toLowerSet(blockedDomains);
        return results.stream().filter(r -> matchFilter(r, allowed, blocked)).toList();
    }

    private static Set<String> toLowerSet(List<String> list) {
        return CollectionUtils.isEmpty(list) ? Collections.emptySet()
                : list.stream().map(String::toLowerCase).collect(Collectors.toSet());
    }

    private static boolean matchFilter(SearchResult r, Set<String> allowed, Set<String> blocked) {
        String url = r.url();
        if (url == null) {
            return false;
        }
        String domain = extractDomain(url);
        if (!allowed.isEmpty() && !matchesAny(domain, allowed)) {
            return false;
        }
        return blocked.isEmpty() || !matchesAny(domain, blocked);
    }

    /** 用 {@link URI} 解析提取 host；解析失败回退到朴素字符串切割。 */
    private static String extractDomain(String url) {
        try {
            String normalized = url;
            String lower = url.toLowerCase();
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                normalized = "https://" + url;
            }
            URI uri = new URI(normalized);
            String host = uri.getHost();
            return host != null ? host.toLowerCase() : fallbackDomain(url);
        } catch (URISyntaxException e) {
            return fallbackDomain(url);
        }
    }

    private static String fallbackDomain(String url) {
        String d = url.toLowerCase();
        if (d.contains("://")) {
            d = d.substring(d.indexOf("://") + 3);
        }
        int slash = d.indexOf('/');
        if (slash >= 0) {
            d = d.substring(0, slash);
        }
        int colon = d.indexOf(':');
        if (colon >= 0) {
            d = d.substring(0, colon);
        }
        return d;
    }

    /** {@code docs.spring.io} 匹配过滤 {@code spring.io}（子域名匹配）。 */
    private static boolean matchesAny(String domain, Set<String> filters) {
        for (String f : filters) {
            if (domain.equals(f) || domain.endsWith("." + f)) {
                return true;
            }
        }
        return false;
    }

    public static Builder builder(String apiKey, String baseUrl) {
        return new Builder(apiKey, baseUrl);
    }

    public static class Builder {
        private final String apiKey;
        private final String baseUrl;
        private int resultCount = 10;

        private Builder(String apiKey, String baseUrl) {
            if (!StringUtils.hasText(apiKey)) {
                throw new IllegalArgumentException("API key must not be null or empty");
            }
            if (!StringUtils.hasText(baseUrl)) {
                throw new IllegalArgumentException("baseUrl must not be null or empty");
            }
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
        }

        public Builder resultCount(int resultCount) {
            if (resultCount <= 0) {
                throw new IllegalArgumentException("resultCount must be positive");
            }
            this.resultCount = resultCount;
            return this;
        }

        public ProxiedBraveWebSearchTool build() {
            return new ProxiedBraveWebSearchTool(apiKey, baseUrl, resultCount);
        }
    }
}
