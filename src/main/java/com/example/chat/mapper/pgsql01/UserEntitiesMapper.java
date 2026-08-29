package com.example.chat.mapper.pgsql01;

import java.util.List;
import java.util.Map;

/**
 * pgsql01 库 {@code user_entities} 表的示例查询，SQL 见 {@code mapper/pgsql01/UserEntitiesMapper.xml}。
 * 仅取一行、原样返回，不做结果解析。
 */
public interface UserEntitiesMapper {

    List<Map<String, Object>> selectSample();
}
