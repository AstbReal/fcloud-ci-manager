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
        if (metaObject.hasSetter("updatedAt")) {
            strictUpdateFill(metaObject, "updatedAt", Instant.class, Instant.now());
        }
    }
}
