package ci.fcloud.manager.template;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * §3.14 模板变量定义
 * 列：id, template_id, var_key, value_type, remark, default_value,
 *     is_required, sort_order（无审计列）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_template_var")
public class TemplateVar extends BaseEntity {

    @TableField("template_id")
    private Long templateId;

    @TableField("var_key")
    private String varKey;

    @TableField("value_type")
    private String valueType;

    @TableField("remark")
    private String remark;

    @TableField("default_value")
    private String defaultValue;

    @TableField("is_required")
    private Boolean isRequired;

    @TableField("sort_order")
    private Integer sortOrder;
}
