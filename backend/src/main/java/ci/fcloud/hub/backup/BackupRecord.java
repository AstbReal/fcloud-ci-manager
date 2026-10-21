package ci.fcloud.hub.backup;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.hub.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.17 备份记录
 * 列：id, file_name, file_path, size, type, created_by, created_at
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

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
}
