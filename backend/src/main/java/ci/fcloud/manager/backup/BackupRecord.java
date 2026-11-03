package ci.fcloud.manager.backup;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.17 备份记录
 * 列：id, file_name, file_path, size, type, created_by, created_at,
 *     status, error_message, finished_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_backup_record")
public class BackupRecord extends BaseEntity {

    @TableField("file_name")
    private String fileName;

    @TableField("file_path")
    private String filePath;

    @TableField("size")
    private Long size;

    /** MANUAL | SCHEDULED */
    @TableField("type")
    private String type;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    /** RUNNING | SUCCESS | FAILED */
    @TableField("status")
    private String status;

    /** 仅 FAILED 时有值 */
    @TableField("error_message")
    private String errorMessage;

    @TableField("finished_at")
    private Instant finishedAt;

    /** NULL = 未上传（开关关闭或从未尝试）| UPLOADED | FAILED —— 与本地备份状态相互独立 */
    @TableField("remote_status")
    private String remoteStatus;

    /** 制品库内的路径，用于下载与清理 */
    @TableField("remote_path")
    private String remotePath;

    @TableField(value = "remote_error", updateStrategy = FieldStrategy.IGNORED)
    private String remoteError;

    @TableField("remote_uploaded_at")
    private Instant remoteUploadedAt;

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
}
