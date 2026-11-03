package ci.fcloud.manager.audit;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * §3.16 流水线访问日志（保留 30 天）
 * 列：id, token_id, application_id, endpoint, method, status, duration_ms, ip, created_at
 *
 * 注意：该表无 created_by / updated_at；created_at 由应用层显式赋值。
 */
@Data
@TableName("fch_access_log")
public class AccessLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("token_id")
    private Long tokenId;

    @TableField("application_id")
    private Long applicationId;

    @TableField("endpoint")
    private String endpoint;

    @TableField("method")
    private String method;

    @TableField("status")
    private Integer status;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("ip")
    private String ip;

    @TableField("created_at")
    private Instant createdAt;
}
