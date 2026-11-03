package ci.fcloud.manager.file;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.9 文件（逻辑文件）
 * 列：id, module_id, environment_id, file_key, remark, current_version,
 *     created_by, created_at, updated_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_file")
public class ModuleFile extends BaseEntity {

    @TableField("module_id")
    private Long moduleId;

    @TableField("environment_id")
    private Long environmentId;

    @TableField("file_key")
    private String fileKey;

    @TableField("remark")
    private String remark;

    @TableField("current_version")
    private Integer currentVersion;

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
