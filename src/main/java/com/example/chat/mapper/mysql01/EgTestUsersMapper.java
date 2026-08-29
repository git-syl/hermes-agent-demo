package com.example.chat.mapper.mysql01;

import java.util.List;
import java.util.Map;

/**
 * mysql01 库 {@code eg_test_users} 表的示例查询，SQL 见 {@code mapper/mysql01/EgTestUsersMapper.xml}。
 * 仅取一行、原样返回，不做结果解析。
 */
public interface EgTestUsersMapper {

    List<Map<String, Object>> selectSample();
}
