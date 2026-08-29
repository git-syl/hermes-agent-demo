package com.example.chat.datasource;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

/**
 * 通用的时间字段自动填充：插入时写 {@code createTime}/{@code updateTime}，更新时刷新 {@code updateTime}。
 *
 * <p>实体对应字段需标注 {@code @TableField(fill = ...)} 才会触发。本类无状态，可被多个数据源的
 * MyBatis-Plus 会话工厂共用——各数据源在自己的 GlobalConfig 上挂同一个实例即可（见各 *DataSourceConfig）。
 * 因为它是项目级的统一审计约定，而非某个库特有，所以放在共享的 datasource 包下。
 */
public class TimeFieldMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
