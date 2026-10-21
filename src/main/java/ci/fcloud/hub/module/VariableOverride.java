package ci.fcloud.hub.module;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.hub.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.8 变量覆盖（环境级 / 订阅应用级）
 * 列：id, variable_id, environment_id, application_id, value, updated_by, updated_at
 * 注意：该表没有 created_at，只有 updated_at。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_variable_override")
public class VariableOverride extends BaseEntity {

    @TableField("variable_id")
    private Long variableId;

    @TableField("environment_id")
    private Long environmentId;

    /** NULL=模块级覆盖；非空=订阅应用级覆盖 */
    @TableField("application_id")
    private Long applicationId;

    @TableField("value")
    private String value;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("updated_at")
    private Instant updatedAt;

    @Override
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
