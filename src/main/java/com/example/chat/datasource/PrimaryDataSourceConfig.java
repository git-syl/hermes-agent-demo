package com.example.chat.datasource;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 主数据源 <b>primary</b>（pgvector 向量库，对应 {@code spring.datasource.*}）+ 其 MyBatis-Plus 装配。
 *
 * <p>本来 Spring Boot 会自动装配这唯一的数据源；一旦项目引入第二数据源
 * （见 {@code com.example.chat.datasource} 下的 {@code Pgsql01/Mysql01DataSourceConfig}），
 * {@code JdbcTemplate}/{@code DataSourceTransactionManager} 等自动配置的
 * {@code @ConditionalOnSingleCandidate(DataSource.class)} 就会因"多个候选"失效，进而拖垮依赖
 * {@code JdbcTemplate} 的 Spring AI pgvector 装配。所以这里显式声明并标注 {@link Primary}：
 * 让所有按类型注入的 {@code DataSource}/{@code JdbcTemplate} 仍默认命中向量库。
 *
 * <p>同时给本库也装一套 MyBatis-Plus 会话工厂（{@code @MapperScan} 到 {@code mapper.primary}），
 * 使得既能被 Spring AI 经 {@code JdbcTemplate} 使用，也能用 MyBatis-Plus 直接查 {@code vector_store}。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(
        basePackages = PrimaryDataSourceConfig.MAPPER_PACKAGE,
        sqlSessionTemplateRef = "primarySqlSessionTemplate")
public class PrimaryDataSourceConfig {

    /** 主库 Mapper 接口所在包：{@code com.example.chat.mapper} 根下的 {@code mapper.primary} 子包。 */
    static final String MAPPER_PACKAGE = "com.example.chat.mapper.primary";

    /** 主库 Mapper XML 位置：与 Java 包对齐的 {@code mapper/primary} 子目录。 */
    private static final String MAPPER_XML_LOCATION = "classpath*:mapper/primary/**/*.xml";

    /**
     * 显式声明并标注 {@link Primary} 的 {@code spring.datasource} 属性。
     * 引入第二数据源后容器中存在多个 {@link DataSourceProperties}，按类型注入会歧义，
     * 故主库统一以本 Bean（{@code @Primary}）为准，消费侧再用 {@code @Qualifier} 显式取。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * url/username/password/driver-class-name 来自 {@link #primaryDataSourceProperties()}；
     * {@code @ConfigurationProperties} 再把 {@code spring.datasource.hikari.*} 的连接池参数
     * 绑到构建出的 HikariDataSource 上。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(
            @Qualifier("primaryDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /** 分页插件，方言 {@link DbType#POSTGRE_SQL}（向量库是 PostgreSQL）。 */
    @Bean
    public MybatisPlusInterceptor primaryMybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 主库的 MyBatis-Plus 会话工厂，绑定 {@code @Primary} 的 {@link #dataSource}。
     * 标注 {@link Primary}：多库共存时按类型注入 {@code SqlSessionFactory} 默认命中本库。
     */
    @Bean
    @Primary
    public SqlSessionFactory primarySqlSessionFactory(
            @Qualifier("dataSource") DataSource dataSource,
            @Qualifier("primaryMybatisPlusInterceptor") MybatisPlusInterceptor primaryMybatisPlusInterceptor)
            throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPlugins(primaryMybatisPlusInterceptor);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources(MAPPER_XML_LOCATION));

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCallSettersOnNulls(true);
        configuration.setDefaultFetchSize(100);
        configuration.setDefaultStatementTimeout(3000);
        factory.setConfiguration(configuration);

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(new TimeFieldMetaObjectHandler());
        factory.setGlobalConfig(globalConfig);

        return factory.getObject();
    }

    @Bean
    @Primary
    public SqlSessionTemplate primarySqlSessionTemplate(
            @Qualifier("primarySqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * 主库事务管理器，绑定 {@code @Primary} 的 {@link #dataSource}。
     *
     * <p>必须显式声明：pgsql01/mysql01 已各自注册了 {@code DataSourceTransactionManager}，Spring Boot
     * 自动配置的事务管理器是 {@code @ConditionalOnMissingBean(TransactionManager.class)}——已存在其它
     * 事务管理器时它就退避，导致主库没有事务管理器、且默认 {@code @Transactional} 因多候选而歧义。
     * 本 Bean 标 {@link Primary}：默认 {@code @Transactional} 即走主库，另两库照旧用名字显式指定。
     */
    @Bean
    @Primary
    public DataSourceTransactionManager primaryTransactionManager(
            @Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
