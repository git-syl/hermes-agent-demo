package com.example.chat.mapper.primary;

import java.util.List;
import java.util.Map;

/**
 * 主库（pgvector）{@code vector_store} 表的示例查询，SQL 见 {@code mapper/primary/VectorStoreMapper.xml}。
 * 仅取一行、原样返回，不做结果解析。
 */
public interface VectorStoreMapper {

    List<Map<String, Object>> selectSample();
}
