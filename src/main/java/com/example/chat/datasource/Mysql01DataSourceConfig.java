package com.example.chat.datasource;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 业务数据源 <b>mysql01</b>（一个 MySQL 实例，库见 {@code docker/mysql-db}）+ 其专属 MyBatis-Plus 装配。
 *
 * <p>命名遵循"引擎 + 序号"约定（与具体库名解耦，详见 {@link Pgsql01DataSourceConfig}）。本类与
 * {@code Pgsql01DataSourceConfig} 完全对称，只差三处：数据源前缀（{@code app.datasource.mysql01}）、
 * 分页方言（{@link DbType#MYSQL}）、Mapper 包/XML 目录（{@code mapper.mysql01} / {@code mapper/mysql01}）。
 *
 * <p>非 {@code @Primary} 数据源；MyBatis-Plus 自动配置因已存在显式 {@link SqlSessionFactory} 而退避，
 * 不会误绑到主库（pgvector 向量库）。
 *
 * <p><b>可选数据源：</b>本库非必需。{@code app.datasource.mysql01.enabled=false} 时整套（数据源、
 * 会话工厂、{@code @MapperScan}、事务管理器）都不装配，没有这个库也能正常启动；缺省（不配该键）视为启用。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.datasource.mysql01", name = "enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(
        basePackages = Mysql01DataSourceConfig.MAPPER_PACKAGE,
        sqlSessionTemplateRef = "mysql01SqlSessionTemplate")
public class Mysql01DataSourceConfig {

    /** 本库 Mapper 接口所在包：{@code com.example.chat.mapper} 根下的 {@code mapper.mysql01} 子包。 */
    static final String MAPPER_PACKAGE = "com.example.chat.mapper.mysql01";

    /** 本库 Mapper XML 位置：与 Java 包对齐的 {@code mapper/mysql01} 子目录。 */
    private static final String MAPPER_XML_LOCATION = "classpath*:mapper/mysql01/**/*.xml";

    @Bean
    @ConfigurationProperties("app.datasource.mysql01")
    public DataSourceProperties mysql01DataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.mysql01.hikari")
    public DataSource mysql01DataSource(
            @Qualifier("mysql01DataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /**
     * 分页 + 乐观锁插件。分页方言固定 {@link DbType#MYSQL}（本库是 MySQL）；
     * 乐观锁仅对带 {@code @Version} 字段的实体生效，无则无副作用。
     */
    @Bean
    public MybatisPlusInterceptor mysql01MybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    public SqlSessionFactory mysql01SqlSessionFactory(
            @Qualifier("mysql01DataSource") DataSource dataSource,
            @Qualifier("mysql01MybatisPlusInterceptor") MybatisPlusInterceptor mysql01MybatisPlusInterceptor)
            throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPlugins(mysql01MybatisPlusInterceptor);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources(MAPPER_XML_LOCATION));

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 让 null 字段也回调 setter，便于查询结果中的 NULL 列正确映射。
        configuration.setCallSettersOnNulls(true);
        configuration.setDefaultFetchSize(100);
        configuration.setDefaultStatementTimeout(3000);
        factory.setConfiguration(configuration);

        // 自动配置已退避，MetaObjectHandler 须显式挂到本工厂；与其它库共用同一时间填充约定。
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(new TimeFieldMetaObjectHandler());
        factory.setGlobalConfig(globalConfig);

        return factory.getObject();
    }

    @Bean
    public SqlSessionTemplate mysql01SqlSessionTemplate(
            @Qualifier("mysql01SqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * 本库专属事务管理器。容器自动配置的 {@code @Primary} 事务管理器只管向量库，
     * 操作本库时需用 {@code @Transactional("mysql01TransactionManager")} 显式指定。
     */
    @Bean
    public DataSourceTransactionManager mysql01TransactionManager(
            @Qualifier("mysql01DataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
