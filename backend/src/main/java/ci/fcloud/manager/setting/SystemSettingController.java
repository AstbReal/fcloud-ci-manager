package ci.fcloud.manager.setting;

import ci.fcloud.manager.audit.AuditService;
import ci.fcloud.manager.common.ApiResponse;
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
    private final ArtifactRepoSettingService artifactSettings;
    private final ArtifactRepoClient artifactRepo;
    private final AuditService auditService;
    private final ci.fcloud.manager.config.FcloudProperties props;

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

    // ---------------- 制品库（JFrog Artifactory 兼容）：备份异地存放 ----------------

    @GetMapping("/artifact")
    public ApiResponse<ArtifactVO> getArtifact() {
        ArtifactRepoSettingService.Config cfg = artifactSettings.load();
        ArtifactVO vo = new ArtifactVO();
        vo.setEnabled(cfg.enabled());
        vo.setBaseUrl(cfg.baseUrl());
        vo.setUsername(cfg.username());
        vo.setRepo(cfg.repo());
        vo.setDir(cfg.dir());
        vo.setHasToken(artifactSettings.hasToken());
        vo.setTokenPreview(artifactSettings.tokenPreview());
        vo.setUpdatedAt(artifactSettings.updatedAt());
        vo.setMaxUploadMb((int) (ArtifactRepoClient.MAX_UPLOAD_BYTES / 1024 / 1024));
        vo.setRetain(props.getBackup().getRetain());
        return ApiResponse.ok(vo);
    }

    @PutMapping("/artifact")
    public ApiResponse<Void> saveArtifact(@RequestBody ArtifactBody body) {
        artifactSettings.save(
                Boolean.TRUE.equals(body.getEnabled()),
                body.getBaseUrl(), body.getUsername(), body.getRepo(),
                body.getDir(), body.getToken());
        auditService.log("SETTING_UPDATE", "SETTING", "artifact", false,
                auditService.detailJson(
                        "enabled", body.getEnabled(),
                        "repo", body.getRepo(),
                        "dir", body.getDir(),
                        "tokenChanged", body.getToken() != null && !body.getToken().isBlank()));
        return ApiResponse.ok();
    }

    /** 用当前配置校验制品库连通性与仓库可读性（不改动已保存的值）。 */
    @PostMapping("/artifact/test")
    public ApiResponse<Map<String, Object>> testArtifact() {
        return ApiResponse.ok(artifactRepo.probe());
    }

    @Data
    public static class ArtifactBody {
        private Boolean enabled;
        private String baseUrl;
        /** 可选：填了就走 Basic（用户名 + Token），否则 Bearer */
        private String username;
        /** 仓库 Key，例如 fcloud-backups-local */
        private String repo;
        /** 仓库内的目录，空则直接放根 */
        private String dir;
        private String token;
    }

    @Data
    public static class ArtifactVO {
        private boolean enabled;
        private String baseUrl;
        private String username;
        private String repo;
        private String dir;
        private boolean hasToken;
        private String tokenPreview;
        private Instant updatedAt;
        private int maxUploadMb;
        /** 备份保留份数（本地与制品库一致），用于页面提示 */
        private int retain;
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
