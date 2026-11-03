package ci.fcloud.manager.token;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.12 流水线 API Token（SHA-256 存储）
 * 列：id, name, token_hash, token_prefix, expires_at,
 *     revoked, created_by, created_at, last_used_at
 * 全局 Token：不绑定应用，可访问全部应用的配置与文件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_api_token")
public class ApiToken extends BaseEntity {

    @TableField("name")
    private String name;

    /** hex(sha256(token)) */
    @TableField("token_hash")
    private String tokenHash;

    /** 展示用，如 chub_ab12 */
    @TableField("token_prefix")
    private String tokenPrefix;

    @TableField("expires_at")
    private Instant expiresAt;

    @TableField("revoked")
    private Boolean revoked;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("last_used_at")
    private Instant lastUsedAt;

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
}
