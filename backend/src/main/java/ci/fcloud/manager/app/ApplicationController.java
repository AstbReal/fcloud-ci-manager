package ci.fcloud.manager.app;

import ci.fcloud.manager.common.ApiResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * §5.3 应用 / 看板。写操作仅系统管理员（/api/console/admin/** 前缀）。
 */
@RestController
@RequestMapping("/api/console/admin/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService service;

    @GetMapping
    public ApiResponse<List<Application>> list() {
        return ApiResponse.ok(service.listAll());
    }

    @PostMapping
    public ApiResponse<Application> create(@Valid @RequestBody Application body) {
        return ApiResponse.ok(service.create(body));
    }

    /** 从 GitLab 项目导入应用。 */
    @PostMapping("/from-gitlab")
    public ApiResponse<Application> importFromGitLab(@RequestBody GitLabImportBody body) {
        return ApiResponse.ok(service.importFromGitLab(body.getProjectId(),
                body.getCode(), body.getName(), body.getDescription(), body.getRepoUrl()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Application> update(@PathVariable Long id,
                                           @RequestBody Application body) {
        return ApiResponse.ok(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @RequestBody(required = false)
                                    DeleteBody body) {
        String confirm = body == null ? null : body.getConfirmName();
        service.delete(id, confirm);
        return ApiResponse.ok();
    }

    @PutMapping("/sort")
    public ApiResponse<Void> sort(@RequestBody SortBody body) {
        service.sort(body.getIds());
        return ApiResponse.ok();
    }

    @Data
    public static class GitLabImportBody {
        private Long projectId;
        private String code;
        private String name;
        private String description;
        private String repoUrl;
    }

    @Data
    public static class DeleteBody {
        private String confirmName;
    }

    @Data
    public static class SortBody {
        private List<Long> ids;
    }
}
