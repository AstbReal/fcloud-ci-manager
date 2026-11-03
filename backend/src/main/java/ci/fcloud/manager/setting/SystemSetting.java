package ci.fcloud.manager.setting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 系统级配置（键值）。主键是字符串 key，独立于 BaseEntity。
 */
@Data
@TableName("fch_system_setting")
public class SystemSetting {

    @TableId(value = "setting_key", type = IdType.INPUT)
    private String settingKey;

    @TableField("setting_value")
    private String settingValue;

    @TableField("encrypted")
    private Boolean encrypted;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("updated_at")
    private Instant updatedAt;
}
