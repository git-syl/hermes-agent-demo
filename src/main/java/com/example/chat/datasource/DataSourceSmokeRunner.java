package com.example.chat.datasource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.example.chat.mapper.mysql01.EgTestUsersMapper;
import com.example.chat.mapper.pgsql01.UserEntitiesMapper;
import com.example.chat.mapper.primary.VectorStoreMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动期多数据源连通性冒烟测试：各库经 MyBatis-Plus(XML) 取一行并打印，用于本地联调/演示。
 *
 * <p>纯打印、不解析结果。设计为对"库可选"友好：
 * <ul>
 *   <li>数据源被关闭（{@code app.datasource.xxx.enabled=false}）时其 Mapper Bean 不存在——这里用
 *       {@link ObjectProvider} 注入，缺失则跳过，不会导致本 Bean 构造失败。</li>
 *   <li>数据源开着但库连不上时，仅告警、不中断启动（某库离线不应拖垮整个应用）。</li>
 * </ul>
 * 正式环境如不需要，删掉本类即可——它不被任何业务代码依赖。
 */
@Component
public class DataSourceSmokeRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSourceSmokeRunner.class);

    private final ObjectProvider<VectorStoreMapper> vectorStoreMapper;   // primary（pgvector 向量库）
    private final ObjectProvider<UserEntitiesMapper> userEntitiesMapper; // pgsql01（可选）
    private final ObjectProvider<EgTestUsersMapper> egTestUsersMapper;   // mysql01（可选）

    public DataSourceSmokeRunner(ObjectProvider<VectorStoreMapper> vectorStoreMapper,
                                 ObjectProvider<UserEntitiesMapper> userEntitiesMapper,
                                 ObjectProvider<EgTestUsersMapper> egTestUsersMapper) {
        this.vectorStoreMapper = vectorStoreMapper;
        this.userEntitiesMapper = userEntitiesMapper;
        this.egTestUsersMapper = egTestUsersMapper;
    }

    @Override
    public void run(String... args) {
        probe("primary/vector_store", vectorStoreMapper, VectorStoreMapper::selectSample);
        probe("pgsql01/user_entities", userEntitiesMapper, UserEntitiesMapper::selectSample);
        probe("mysql01/eg_test_users", egTestUsersMapper, EgTestUsersMapper::selectSample);
    }

    private <T> void probe(String source, ObjectProvider<T> provider,
                           Function<T, List<Map<String, Object>>> query) {
        T mapper = provider.getIfAvailable();
        if (mapper == null) {
            log.info("[数据源冒烟] {} 未启用，跳过", source);
            return;
        }
        try {
            log.info("[数据源冒烟] {} -> {}", source, query.apply(mapper));
        } catch (Exception e) {
            log.warn("[数据源冒烟] {} 查询失败：{}", source, e.getMessage());
        }
    }
}
