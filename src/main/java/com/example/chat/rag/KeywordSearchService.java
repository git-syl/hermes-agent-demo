package com.example.chat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 关键词（全文）检索腿：直接查 Spring AI PgVectorStore 的 {@code vector_store} 表，
 * 用 PostgreSQL 内置全文检索对 {@code content} 做精确词命中，按 {@code ts_rank_cd} 排序。
 *
 * <p>这是混合检索的关键词侧，补向量检索的短板——专有名词、英文缩写、数字编号等"字面命中"。
 * 仅当 {@code chat.rag.hybrid.enabled=true} 时注册为 Bean。
 *
 * <p>租户隔离用 {@code metadata ->> 'tenantId'}；分词配置由 {@code chat.rag.hybrid.ts-config} 决定，
 * 默认 {@code simple}（跨语言通用）。中文需更好分词时安装 zhparser/pg_jieba 并把配置指过去。
 *
 * @see <a href="https://www.postgresql.org/docs/current/textsearch.html">PostgreSQL Full Text Search</a>
 */
@Service
@ConditionalOnProperty(prefix = "chat.rag.hybrid", name = "enabled", havingValue = "true")
public class KeywordSearchService {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchService.class);

    // 用 cast(:cfg as regconfig) 而非 :cfg::regconfig，避开命名参数解析对 "::" 的歧义。
    private static final String SQL_HEAD = """
            SELECT id::text AS id, content, metadata::text AS metadata
            FROM vector_store
            WHERE metadata ->> 'tenantId' = :tenantId
            """;

    private static final String SQL_TAIL = """
              AND to_tsvector(cast(:cfg as regconfig), content)
                  @@ websearch_to_tsquery(cast(:cfg as regconfig), :q)
            ORDER BY ts_rank_cd(
                       to_tsvector(cast(:cfg as regconfig), content),
                       websearch_to_tsquery(cast(:cfg as regconfig), :q)) DESC
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RagProperties.Hybrid props;

    public KeywordSearchService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                RagProperties ragProperties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.props = ragProperties.getHybrid();
    }

    /**
     * 在指定租户内对 {@code content} 做全文检索，返回按相关度降序的前 {@code limit} 条文档块。
     * 查询为空或无命中时返回空列表。
     *
     * @param filters 业务元数据过滤（如 category=redis），与 {@code tenantId} 做 AND；可为空。
     *                键/值均以命名参数绑定（{@code metadata ->> :k = :v}），无注入风险。
     */
    public List<Document> search(String query, String tenantId, Map<String, Object> filters, int limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        // TODO(P2 健壮性): 关键词腿失败（如 ts-config 配错、全文查询异常）目前会冒泡导致整个检索 500；
        //                 可考虑 try/catch 优雅降级为纯向量（仅记 WARN），与 rerank 的降级策略保持一致。
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cfg", props.getTsConfig())
                .addValue("q", query)
                .addValue("limit", limit);
        String sql = SQL_HEAD + filterClause(filters, params) + SQL_TAIL;
        return jdbc.query(sql, params, (rs, rowNum) ->
                Document.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .metadata(parseMetadata(rs.getString("metadata")))
                        .build());
    }

    /**
     * 把业务过滤拼成 {@code AND metadata ->> :mkN = :mvN} 子句，键值都用命名参数绑定。
     * {@code ->>} 取出的是文本，故比较值统一转成字符串；跳过保留键 {@code tenantId} 与空值。
     */
    private static String filterClause(Map<String, Object> filters, MapSqlParameterSource params) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        StringBuilder clause = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            if (e.getValue() == null || "tenantId".equals(e.getKey())) {
                continue;
            }
            clause.append("  AND metadata ->> :mk").append(i).append(" = :mv").append(i).append('\n');
            params.addValue("mk" + i, e.getKey());
            params.addValue("mv" + i, String.valueOf(e.getValue()));
            i++;
        }
        return clause.toString();
    }

    /** 把 {@code metadata} json 列解析为 Map；解析失败不致命，退化为空 Map 并继续。 */
    private Map<String, Object> parseMetadata(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        }
        catch (Exception e) {
            log.warn("解析 vector_store.metadata 失败，按空元数据处理：{}", e.getMessage());
            return Map.of();
        }
    }
}
