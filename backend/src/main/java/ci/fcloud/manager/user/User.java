package ci.fcloud.manager.user;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.1 用户（email 唯一标识，来自 GitLab）
 * 列：id, email, name, nickname, avatar_url, gitlab_id, global_role,
 *     last_login_at, created_at, updated_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_user")
public class User extends BaseEntity {

    @TableField("email")
    private String email;

    @TableField("name")
    private String name;

    @TableField("nickname")
    private String nickname;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("gitlab_id")
    private String gitlabId;

    /** SYSTEM_ADMIN | NONE */
    @TableField("global_role")
    private String globalRole;

    @TableField("last_login_at")
    private Instant lastLoginAt;

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
