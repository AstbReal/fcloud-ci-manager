package ci.fcloud.manager.mcp;

import java.time.Instant;
import java.util.Map;

import ci.fcloud.manager.audit.AuditService;
import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.common.SecurityUtils;
import ci.fcloud.manager.config.FcloudProperties;
import ci.fcloud.manager.mcp.tool.ToolRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 运行时开关（仅系统管理员，路径在 /api/console/admin 下）。
 */
@RestController
@RequestMapping("/api/console/admin/mcp")
@RequiredArgsConstructor
public class McpSettingController {

    private final McpSettingService settings;
    private final ToolRegistry registry;
    private final FcloudProperties props;
    private final AuditService auditService;

    @GetMapping
    public ApiResponse<McpVO> get() {
        McpSettingService.Snapshot s = settings.snapshot();
        McpVO vo = new McpVO();
        vo.setEnabled(s.enabled());
        vo.setWriteEnabled(s.writeEnabled());
        vo.setRevealEnabled(s.revealEnabled());
        vo.setPersisted(settings.persisted());
        vo.setUpdatedAt(settings.updatedAt());
        vo.setToolCounts(registry.countByScope());

        Limits limits = new Limits();
        // 用 int：项目把 Long 全局序列化为字符串（雪花 ID 精度），这两个值用 int 保持 JSON 数字
        limits.setRatePerMinute((int) props.getMcp().getRatePerMinute());
        limits.setMaxResultBytes(props.getMcp().getMaxResultBytes());
        limits.setSessionTtlMinutes((int) props.getMcp().getSessionTtlMinutes());
        vo.setLimits(limits);
        return ApiResponse.ok(vo);
    }

    @PutMapping
    public ApiResponse<Void> save(@RequestBody ToggleBody body) {
        if (body.getEnabled() == null && body.getWriteEnabled() == null
                && body.getRevealEnabled() == null) {
            return ApiResponse.ok();
        }
        settings.save(body.getEnabled(), body.getWriteEnabled(), body.getRevealEnabled(),
                SecurityUtils.currentUserId());
        auditService.log("SETTING_UPDATE", "SETTING", "mcp", false,
                auditService.detailJson(
                        "enabled", body.getEnabled(),
                        "writeEnabled", body.getWriteEnabled(),
                        "revealEnabled", body.getRevealEnabled()));
        return ApiResponse.ok();
    }

    @Data
    public static class ToggleBody {
        private Boolean enabled;
        private Boolean writeEnabled;
        private Boolean revealEnabled;
    }

    @Data
    public static class McpVO {
        private boolean enabled;
        private boolean writeEnabled;
        private boolean revealEnabled;
        /** false 表示还沿用环境变量/配置文件，页面没改过 */
        private boolean persisted;
        private Instant updatedAt;
        private Map<String, Integer> toolCounts;
        private Limits limits;
    }

    @Data
    public static class Limits {
        private int ratePerMinute;
        private int maxResultBytes;
        private int sessionTtlMinutes;
    }
}
