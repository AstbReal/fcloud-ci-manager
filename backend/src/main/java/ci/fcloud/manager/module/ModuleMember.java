package ci.fcloud.manager.module;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.11 模块成员（模块级角色）
 * 列：id, module_id, user_id, role, created_by, created_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_module_member")
public class ModuleMember extends BaseEntity {

    @TableField("module_id")
    private Long moduleId;

    @TableField("user_id")
    private Long userId;

    /** MODULE_ADMIN | MODULE_OPERATOR */
    @TableField("role")
    private String role;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
}
