/**
 * mysql01 数据源的 MyBatis-Plus Mapper 接口包。
 *
 * <p>包规则：所有数据源的 Mapper 统一放在 {@code com.example.chat.mapper} 根下，按数据源标识
 * （引擎+序号，与业务库名解耦）再分子包（本数据源 = {@code mapper.mysql01}）。本包内的接口由
 * {@link com.example.chat.datasource.Mysql01DataSourceConfig} 上的 {@code @MapperScan} 扫描，
 * 绑定到 mysql01 会话工厂；对应 Mapper XML 放在 {@code src/main/resources/mapper/mysql01/}。
 */
package com.example.chat.mapper.mysql01;
