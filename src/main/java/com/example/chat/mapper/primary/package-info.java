/**
 * primary 数据源（pgvector 向量库）的 MyBatis-Plus Mapper 接口包。
 *
 * <p>包规则：所有数据源的 Mapper 统一放在 {@code com.example.chat.mapper} 根下，按数据源标识再分子包
 * （主库 = {@code mapper.primary}）。本包内的接口由
 * {@link com.example.chat.datasource.PrimaryDataSourceConfig} 上的 {@code @MapperScan} 扫描，
 * 绑定到主库会话工厂；对应 Mapper XML 放在 {@code src/main/resources/mapper/primary/}。
 */
package com.example.chat.mapper.primary;
