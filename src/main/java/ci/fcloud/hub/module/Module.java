package ci.fcloud.hub.module;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.hub.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.4 模块（私有 application_id 非空；共享为空）
 * 列：id, application_id, code, name, description, is_shared, group_name,
 *     template_id, template_synced_at, sort_order, created_by, created_at, updated_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_module")
public class Module extends BaseEntity {

    /** NULL = 共享模块 */
    @TableField("application_id")
    private Long applicationId;

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("is_shared")
    private Boolean isShared;

    /** 看板内分组 */
    @TableField("group_name")
    private String groupName;

    /** 软关联 fch_template */
    @TableField("template_id")
    private Long templateId;

    @TableField("template_synced_at")
    private Instant templateSyncedAt;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
