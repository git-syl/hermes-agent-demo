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
 * 业务数据源 <b>pgsql01</b>（一个 PostgreSQL 实例）+ 其专属的 MyBatis-Plus 装配。
 *
 * <p><b>命名约定：</b>数据源标识一律用"引擎 + 序号"（{@code pgsql01}、{@code pgsql02}、{@code mysql01}…），
 * 与具体业务库名解耦——库名可能改、可能多个用途共享一个库，但数据源标识应稳定。再接同类型库时序号递增即可。
 * 这里的 {@code pgsql01} 当前指向 {@code ai-sport} 库（见 {@code app.datasource.pgsql01}）。
 *
 * <p><b>为什么手写而不靠 starter 自动配置：</b>项目主数据源是 pgvector 向量库（{@code @Primary}，
 * 见 {@link PrimaryDataSourceConfig}）。MyBatis-Plus 自动配置会把 {@code SqlSessionFactory} 绑到
 * {@code @Primary} 数据源上——那是向量库，并非业务库。因此这里显式声明 {@link SqlSessionFactory}：
 * 其自动配置侧是 {@code @ConditionalOnMissingBean(SqlSessionFactory.class)}，我们提供后即整体退避，
 * 保证 MyBatis-Plus 只作用于本数据源，绝不碰向量库。
 *
 * <p><b>多数据源扩展约定（照抄本类即可）：</b>每个库 = 一个自包含 {@code @Configuration}，各持有：
 * ①独立 {@code DataSource}（非 {@code @Primary}）；②独立 {@link MybatisPlusInterceptor}（按库选
 * {@link DbType}）；③独立 {@code SqlSessionFactory}/{@code SqlSessionTemplate}；④独立事务管理器；
 * ⑤通过 {@link MapperScan} 把"专属 Mapper 包 + XML 目录"绑到本库会话工厂。各库 Mapper 位置、方言、
 * 事务边界天然隔离。新增库只需换三处：数据源前缀、{@link DbType}、Mapper 包/XML 目录。
 *
 * <p><b>可选数据源：</b>本库非必需。{@code app.datasource.pgsql01.enabled=false} 时整套（数据源、
 * 会话工厂、{@code @MapperScan}、事务管理器）都不装配，没有这个库也能正常启动；缺省（不配该键）视为启用。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.datasource.pgsql01", name = "enabled", havingValue = "true", matchIfMissing = true)
@MapperScan(
        basePackages = Pgsql01DataSourceConfig.MAPPER_PACKAGE,
        sqlSessionTemplateRef = "pgsql01SqlSessionTemplate")
public class Pgsql01DataSourceConfig {

    /** 本库 Mapper 接口所在包：{@code com.example.chat.mapper} 根下的 {@code mapper.pgsql01} 子包。 */
    static final String MAPPER_PACKAGE = "com.example.chat.mapper.pgsql01";

    /** 本库 Mapper XML 位置：与 Java 包对齐的 {@code mapper/pgsql01} 子目录。 */
    private static final String MAPPER_XML_LOCATION = "classpath*:mapper/pgsql01/**/*.xml";

    @Bean
    @ConfigurationProperties("app.datasource.pgsql01")
    public DataSourceProperties pgsql01DataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.pgsql01.hikari")
    public DataSource pgsql01DataSource(
            @Qualifier("pgsql01DataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /**
     * 分页 + 乐观锁插件。分页方言固定 {@link DbType#POSTGRE_SQL}（本库是 PostgreSQL）；
     * 乐观锁仅对带 {@code @Version} 字段的实体生效，无则无副作用。
     */
    @Bean
    public MybatisPlusInterceptor pgsql01MybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    public SqlSessionFactory pgsql01SqlSessionFactory(
            @Qualifier("pgsql01DataSource") DataSource dataSource,
            @Qualifier("pgsql01MybatisPlusInterceptor") MybatisPlusInterceptor pgsql01MybatisPlusInterceptor)
            throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPlugins(pgsql01MybatisPlusInterceptor);
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
    public SqlSessionTemplate pgsql01SqlSessionTemplate(
            @Qualifier("pgsql01SqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * 本库专属事务管理器。容器自动配置的 {@code @Primary} 事务管理器只管向量库，
     * 操作本库时需用 {@code @Transactional("pgsql01TransactionManager")} 显式指定。
     */
    @Bean
    public DataSourceTransactionManager pgsql01TransactionManager(
            @Qualifier("pgsql01DataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
