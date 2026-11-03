package ci.fcloud.manager.env;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.2 环境（全局，自引用继承链）
 * 列：id, code, name, color, sort_order, is_default, parent_env_id, created_at, updated_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_environment")
public class Environment extends BaseEntity {

    /** dev/test/prod */
    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    @TableField("color")
    private String color;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("is_default")
    private Boolean isDefault;

    /** 父环境，构成继承链 */
    @TableField("parent_env_id")
    private Long parentEnvId;

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

    /**
     * 非持久化字段：标记请求体是否显式携带了 parentEnvId。
     * 用于区分"不修改继承关系"（未携带）与"解除继承"（携带 null）。
     */
    @TableField(exist = false)
    private boolean parentEnvIdSet;

    /** 绑定该环境的模块数（仅列表展示，不落库）。 */
    @TableField(exist = false)
    private Integer moduleCount;

    /**
     * Jackson 反序列化入口：无论值是 null 还是具体 id，都视为"显式设置"。
     */
    public void setParentEnvId(Long parentEnvId) {
        this.parentEnvId = parentEnvId;
        this.parentEnvIdSet = true;
    }

    public boolean isParentEnvIdSet() {
        return parentEnvIdSet;
    }
}
