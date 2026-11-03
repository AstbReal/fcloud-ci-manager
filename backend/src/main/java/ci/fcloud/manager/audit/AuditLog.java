package ci.fcloud.manager.audit;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.15 审计日志（保留 7 天）
 * 列：id, user_id, user_email, action, target_type, target_id, detail,
 *     is_critical, ip, created_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_audit_log")
public class AuditLog extends BaseEntity {

    @TableField("user_id")
    private Long userId;

    @TableField("user_email")
    private String userEmail;

    @TableField("action")
    private String action;

    @TableField("target_type")
    private String targetType;

    @TableField("target_id")
    private String targetId;

    /** JSON 字符串 */
    @TableField("detail")
    private String detail;

    @TableField("is_critical")
    private Boolean isCritical;

    @TableField("ip")
    private String ip;

    @TableField("created_at")
    private Instant createdAt;

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
}
