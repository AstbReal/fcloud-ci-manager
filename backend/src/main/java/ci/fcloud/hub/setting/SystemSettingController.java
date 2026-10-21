package ci.fcloud.hub.setting;

import ci.fcloud.hub.audit.AuditService;
import ci.fcloud.hub.common.ApiResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 系统管理：GitLab 接入配置（仅系统管理员，路径在 /api/console/admin 下）。
 */
@RestController
@RequestMapping("/api/console/admin/settings")
@RequiredArgsConstructor
public class SystemSettingController {

    private final GitLabSettingService settings;
    private final GitLabClient gitLab;
    private final AuditService auditService;

    @GetMapping("/gitlab")
    public ApiResponse<GitLabVO> get() {
        GitLabSettingService.Config cfg = settings.load();
        GitLabVO vo = new GitLabVO();
        vo.setBaseUrl(cfg.baseUrl());
        vo.setHasToken(settings.hasToken());
        vo.setTokenPreview(settings.tokenPreview());
        vo.setUpdatedAt(settings.updatedAt());
        return ApiResponse.ok(vo);
    }

    @PutMapping("/gitlab")
    public ApiResponse<Void> save(@RequestBody GitLabBody body) {
        settings.save(body.getBaseUrl(), body.getToken());
        auditService.log("SETTING_UPDATE", "SETTING", "gitlab", false,
                auditService.detailJson(
                        "baseUrl", body.getBaseUrl(),
                        "tokenChanged", body.getToken() != null && !body.getToken().isBlank()));
        return ApiResponse.ok();
    }

    /** 立即用当前配置校验一次连通性。 */
    @PostMapping("/gitlab/test")
    public ApiResponse<Map<String, Object>> test() {
        return ApiResponse.ok(gitLab.me());
    }

    @GetMapping("/gitlab/projects")
    public ApiResponse<List<GitLabClient.Project>> projects(
            @RequestParam(required = false) String search) {
        return ApiResponse.ok(gitLab.projects(search));
    }

    @Data
    public static class GitLabBody {
        private String baseUrl;
        private String token;
    }

    @Data
    public static class GitLabVO {
        private String baseUrl;
        private boolean hasToken;
        private String tokenPreview;
        private Instant updatedAt;
    }
}
