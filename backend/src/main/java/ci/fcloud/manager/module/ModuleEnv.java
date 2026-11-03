package ci.fcloud.manager.module;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import ci.fcloud.manager.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * §3.6 模块 × 环境绑定（模块出现在哪些环境）
 * 列：id, module_id, environment_id（无审计列）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fch_module_env")
public class ModuleEnv extends BaseEntity {

    @TableField("module_id")
    private Long moduleId;

    @TableField("environment_id")
    private Long environmentId;
}
