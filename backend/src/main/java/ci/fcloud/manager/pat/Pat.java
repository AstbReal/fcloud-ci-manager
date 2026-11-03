package ci.fcloud.manager.pat;

import java.time.Instant;

import ci.fcloud.manager.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * MCP 个人访问令牌。
 *
 * <p>与 {@link ci.fcloud.manager.token.ApiToken} 的区别：本表绑定真实用户，
 * 权限跟随该用户角色 + scope；流水线 Token 是全局只读的机器凭证。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_pat")
public class Pat extends BaseEntity {

    @TableField("user_id")
    private Long userId;

    @TableField("name")
    private String name;

    /** hex(sha256(明文)) */
    @TableField("token_hash")
    private String tokenHash;

    /** 展示用：chub_pat_ + 前 8 位 */
    @TableField("token_prefix")
    private String tokenPrefix;

    /** 逗号分隔：mcp:read / mcp:write / mcp:secret */
    @TableField("scopes")
    private String scopes;

    @TableField("expires_at")
    private Instant expiresAt;

    @TableField("revoked")
    private Boolean revoked;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("last_used_at")
    private Instant lastUsedAt;

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
}
