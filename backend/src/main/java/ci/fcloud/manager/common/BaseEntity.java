package ci.fcloud.manager.common;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * 通用基类。主键 BIGINT 雪花（ASSIGN_ID）。
 *
 * 设计书 §3 的各表审计列并不一致，因此 createdAt / updated_at 一律按表 opt-in：
 * <pre>
 *   created_at + updated_at : fch_user, fch_environment, fch_application,
 *                             fch_module, fch_variable, fch_file, fch_template
 *   仅 created_at           : fch_module_subscription, fch_module_member,
 *                             fch_file_version, fch_api_token, fch_audit_log,
 *                             fch_access_log, fch_backup_record
 *   仅 updated_at           : fch_variable_override
 *   都没有                  : fch_module_env, fch_template_var
 * </pre>
 *
 * 需要的子类覆写 getter 并加 {@code @TableField("...")}；
 * 基类默认返回 null 且不映射，避免 SQL 引用不存在的列。
 *
 * 另外 §3 的 schema 没有逻辑删除列，不启用 @TableLogic；
 * 删除一律真实删除 + 级联（§5.3/§5.4）。
 */
@Data
public class BaseEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 有 created_at 列的表由子类覆写。 */
    public Instant getCreatedAt() {
        return null;
    }

    /** 有 updated_at 列的表由子类覆写。 */
    public Instant getUpdatedAt() {
        return null;
    }
}
