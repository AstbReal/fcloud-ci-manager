package ci.fcloud.manager.template;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * 模板文件定义（与环境无关）。
 *
 * <p>模板只保存一份当前内容；下发/同步时复制到模块已绑定的每个环境，
 * 生成 {@code fch_file} + {@code fch_file_version}。模块侧用
 * {@code origin = TEMPLATE} 标记这些副本。</p>
 *
 * 列：id, template_id, file_key, remark, storage_path, original_name,
 *     mime_type, size, sha256, created_by, created_at, updated_at
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_template_file")
public class TemplateFile extends BaseEntity {

    @TableField("template_id")
    private Long templateId;

    @TableField("file_key")
    private String fileKey;

    @TableField("remark")
    private String remark;

    /** 相对 storage.root 的路径：_templates/{templateId}/{fileKey} */
    @TableField("storage_path")
    private String storagePath;

    @TableField("original_name")
    private String originalName;

    @TableField("mime_type")
    private String mimeType;

    @TableField("size")
    private Long size;

    /** SHA-256（hex），用于同步时判断内容是否变化 */
    @TableField("sha256")
    private String sha256;

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
