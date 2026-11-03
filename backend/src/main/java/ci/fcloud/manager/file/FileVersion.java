package ci.fcloud.manager.file;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * §3.10 文件版本（保留最近 3 个）
 * 列：id, file_id, version, storage_path, original_name, mime_type, size,
 *     uploaded_by, created_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_file_version")
public class FileVersion extends BaseEntity {

    @TableField("file_id")
    private Long fileId;

    @TableField("version")
    private Integer version;

    /** 相对 storage.root 的路径 */
    @TableField("storage_path")
    private String storagePath;

    @TableField("original_name")
    private String originalName;

    @TableField("mime_type")
    private String mimeType;

    @TableField("size")
    private Long size;

    @TableField("uploaded_by")
    private Long uploadedBy;

    @TableField("created_at")
    private Instant createdAt;

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
}
