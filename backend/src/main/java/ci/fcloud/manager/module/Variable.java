package ci.fcloud.manager.module;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.7 变量（模块内 key 唯一）
 * 列：id, module_id, var_key, value_type, global_value, remark, is_required,
 *     sort_order, created_by, created_at, updated_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_variable")
public class Variable extends BaseEntity {

    @TableField("module_id")
    private Long moduleId;

    @TableField("var_key")
    private String varKey;

    /** PLAIN | ENCRYPTED */
    @TableField("value_type")
    private String valueType;

    /** NULL = 无全局值 */
    @TableField("global_value")
    private String globalValue;

    @TableField("remark")
    private String remark;

    @TableField("is_required")
    private Boolean isRequired;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
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
