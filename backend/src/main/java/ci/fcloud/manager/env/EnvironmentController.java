package ci.fcloud.manager.env;

import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.common.CurrentUser;
import ci.fcloud.manager.common.SecurityUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * §5.2 环境管理。写操作仅系统管理员（SecurityConfig 的 /api/console/admin/** 前缀）。
 */
@RestController
@RequestMapping("/api/console/admin/environments")
@RequiredArgsConstructor
public class EnvironmentController {

    private final EnvironmentService service;
    private final ci.fcloud.manager.module.ModuleEnvMapper moduleEnvMapper;

    @GetMapping
    public ApiResponse<List<Environment>> list() {
        List<Environment> list = service.listAll();
        java.util.Map<Long, Long> counts = moduleEnvMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ci.fcloud.manager.module.ModuleEnv::getEnvironmentId,
                        java.util.stream.Collectors.counting()));
        list.forEach(e -> e.setModuleCount(
                counts.getOrDefault(e.getId(), 0L).intValue()));
        return ApiResponse.ok(list);
    }

    @PostMapping
    public ApiResponse<Environment> create(@Valid @RequestBody Environment body) {
        CurrentUser cu = SecurityUtils.currentUser();
        return ApiResponse.ok(service.create(body, cu.getUid()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Environment> update(@PathVariable Long id,
                                           @Valid @RequestBody Environment body) {
        return ApiResponse.ok(service.update(id, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok();
    }

    @PutMapping("/sort")
    public ApiResponse<Void> sort(@RequestBody SortBody body) {
        service.sort(body.getIds());
        return ApiResponse.ok();
    }

    @Data
    public static class SortBody {
        private List<Long> ids;
    }
}
