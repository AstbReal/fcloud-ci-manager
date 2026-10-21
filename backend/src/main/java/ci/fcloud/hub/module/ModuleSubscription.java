package ci.fcloud.hub.module;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.hub.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.5 共享模块订阅（引用制，实时跟随源模块）
 * 列：id, module_id, application_id, created_by, created_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_module_subscription")
public class ModuleSubscription extends BaseEntity {

    @TableField("module_id")
    private Long moduleId;

    @TableField("application_id")
    private Long applicationId;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
}
