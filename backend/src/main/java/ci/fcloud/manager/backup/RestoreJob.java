package ci.fcloud.manager.backup;

import ci.fcloud.manager.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * 备份恢复任务（异步）。
 * 上传包先落到临时路径，后台线程解包导入，结束后删除临时文件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_restore_job")
public class RestoreJob extends BaseEntity {

    @TableField("file_name")
    private String fileName;

    @TableField("file_path")
    private String filePath;

    @TableField("size")
    private Long size;

    /** ABORT | MERGE | OVERWRITE */
    @TableField("strategy")
    private String strategy;

    /** RUNNING | SUCCESS | FAILED */
    @TableField("status")
    private String status;

    @TableField("error_message")
    private String errorMessage;

    /** 导入的记录条数 */
    @TableField("records")
    private Integer records;

    /** 解包的文件二进制数 */
    @TableField("files")
    private Integer files;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("finished_at")
    private Instant finishedAt;

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
}
