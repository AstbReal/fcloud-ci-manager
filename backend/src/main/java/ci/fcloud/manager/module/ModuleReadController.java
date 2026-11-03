package ci.fcloud.manager.module;

import ci.fcloud.manager.app.Application;
import ci.fcloud.manager.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * §5.4 模块的登录可读端点（区别于 /api/console/admin/** 的仅系统管理员端点）。
 * 权限：查看（模块成员或系统管理员）由 PermissionService 控制。
 */
@RestController
@RequestMapping("/api/console")
@RequiredArgsConstructor
public class ModuleReadController {

    private final ModuleService moduleService;
    private final PermissionService permissionService;

    /**
     * §5.4 GET /modules/{id}/subscribers 共享模块订阅方（登录可读）。
     */
    @GetMapping("/modules/{id}/subscribers")
    public ApiResponse<List<Application>> subscribers(@PathVariable Long id) {
        permissionService.requireCanView(id);
        return ApiResponse.ok(moduleService.subscribersOf(id));
    }
}
