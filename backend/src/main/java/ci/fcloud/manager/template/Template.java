package ci.fcloud.manager.template;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.13 模板（变量定义集合，仅系统管理员可维护）
 * 列：id, code, name, description, created_by, created_at, updated_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_template")
public class Template extends BaseEntity {

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("group_name")
    private String groupName;

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
