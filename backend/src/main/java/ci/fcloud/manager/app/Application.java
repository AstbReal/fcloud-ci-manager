package ci.fcloud.manager.app;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.3 应用（一个部署单元）
 * 列：id, code, name, description, sort_order, created_by, created_at, updated_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_application")
public class Application extends BaseEntity {

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("sort_order")
    private Integer sortOrder;

    /** 来源 GitLab 项目 id（从仓库导入时写入） */
    @TableField("gitlab_project_id")
    private Long gitlabProjectId;

    /**
     * 仓库地址，便于从应用跳回代码。
     * updateStrategy 必须忽略 null：MyBatis-Plus 的 updateById 默认跳过 null 字段，
     * 否则「清空仓库地址」这个操作永远不生效。
     */
    @TableField(value = "repo_url", updateStrategy = FieldStrategy.IGNORED)
    private String repoUrl;

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
