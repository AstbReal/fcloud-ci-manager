package ci.fcloud.manager.common;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 自动填充 createdAt / updated_at（对应 schema 的 TIMESTAMPTZ DEFAULT now()）。
 *
 * BaseEntity 基类不声明这两个字段，由需要的子类覆写 getter 并加
 * {@code @TableField("...")}。这里用 hasSetter 判断该实体是否真的有对应字段，
 * 避免 SQL 引用不存在的列（见 BaseEntity 的列说明）。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now();
        if (metaObject.hasSetter("createdAt")) {
            strictInsertFill(metaObject, "createdAt", Instant.class, now);
        }
        if (metaObject.hasSetter("updatedAt")) {
            strictInsertFill(metaObject, "updatedAt", Instant.class, now);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 这里必须用 setFieldValByName 而不是 strictUpdateFill：
        // strictUpdateFill 只在字段为 null 时才填，而业务代码普遍是
        // 「requireById 读出实体 → 改几个字段 → updateById」，实体上带着数据库里的旧
        // updatedAt，strict 判定「已有值」直接跳过，更新时间就永远不刷新（实测过）。
        if (metaObject.hasSetter("updatedAt")) {
            setFieldValByName("updatedAt", Instant.now(), metaObject);
        }
    }
}
