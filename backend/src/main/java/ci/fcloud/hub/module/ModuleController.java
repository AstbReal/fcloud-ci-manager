package ci.fcloud.hub.module;

import ci.fcloud.hub.app.Application;
import ci.fcloud.hub.app.ApplicationMapper;
import ci.fcloud.hub.app.ApplicationService;
import ci.fcloud.hub.common.ApiResponse;
import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.common.SecurityUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * §5.4 模块 / 订阅 / 分组 / 复制。写操作仅系统管理员。
 */
@RestController
@RequestMapping("/api/console/admin")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleService moduleService;
    private final ApplicationService applicationService;
    private final ApplicationMapper applicationMapper;
    private final VariableMapper variableMapper;
    private final ModuleEnvMapper moduleEnvMapper;

    // ---- 应用下模块 ----

    @PostMapping("/applications/{appId}/modules")
    public ApiResponse<Module> createPrivate(@PathVariable Long appId,
                                             @RequestBody CreateBody body) {
        return ApiResponse.ok(moduleService.createPrivate(appId, body.toModule(),
                body.getEnvironmentIds()));
    }

    @GetMapping("/applications/{appId}/modules")
    public ApiResponse<List<Module>> listByApp(@PathVariable Long appId) {
        applicationService.requireById(appId);
        return ApiResponse.ok(moduleService.listByApplication(appId));
    }

    // ---- 共享模块 ----

    @GetMapping("/shared-modules")
    public ApiResponse<List<Module>> listShared() {
        return ApiResponse.ok(moduleService.listShared());
    }

    @PostMapping("/shared-modules")
    public ApiResponse<Module> createShared(@RequestBody CreateBody body) {
        return ApiResponse.ok(moduleService.createShared(body.toModule(),
                body.getEnvironmentIds()));
    }

    // ---- 模块详情 / 改 / 删 ----

    /**
     * 模块总览：私有模块 + 共享模块，带变量数与环境数。
     * 供「模块列表」页使用。
     */
    @GetMapping("/modules")
    public ApiResponse<List<ModuleListVO>> listAll() {
        List<Module> modules = moduleService.lambdaQuery()
                .orderByAsc(Module::getSortOrder)
                .orderByAsc(Module::getId)
                .list();
        java.util.Map<Long, String> appCodes = new java.util.HashMap<>();
        for (Application a : applicationMapper.selectList(null)) {
            appCodes.put(a.getId(), a.getCode());
        }
        return ApiResponse.ok(modules.stream().map(m -> {
            ModuleListVO vo = new ModuleListVO();
            vo.setId(m.getId());
            vo.setCode(m.getCode());
            vo.setName(m.getName());
            vo.setDescription(m.getDescription());
            vo.setIsShared(m.getIsShared());
            vo.setGroupName(m.getGroupName());
            vo.setApplicationId(m.getApplicationId());
            vo.setApplicationCode(m.getApplicationId() == null
                    ? null : appCodes.get(m.getApplicationId()));
            vo.setUpdatedAt(m.getUpdatedAt());
            vo.setVariableCount(variableMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                            .eq(Variable::getModuleId, m.getId())).intValue());
            vo.setEnvironmentCount(moduleEnvMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                            .eq(ModuleEnv::getModuleId, m.getId())).intValue());
            return vo;
        }).toList());
    }

    @GetMapping("/modules/{id}")
    public ApiResponse<ModuleDetail> detail(@PathVariable Long id) {
        Module m = moduleService.requireById(id);
        ModuleDetail d = ModuleDetail.of(m, moduleService);
        if (m.getApplicationId() != null) {
            Application app = applicationMapper.selectById(m.getApplicationId());
            if (app != null) {
                d.setApplicationCode(app.getCode());
                d.setApplicationName(app.getName());
            }
        }
        d.setUpdatedAt(m.getUpdatedAt());
        return ApiResponse.ok(d);
    }

    @PutMapping("/modules/{id}")
    public ApiResponse<Module> update(@PathVariable Long id, @RequestBody UpdateBody body) {
        return ApiResponse.ok(moduleService.update(id, body.toModule(),
                body.getEnvironmentIds()));
    }

    @DeleteMapping("/modules/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        moduleService.delete(id);
        return ApiResponse.ok();
    }

    @PutMapping("/modules/group")
    public ApiResponse<Void> renameGroup(@RequestBody GroupRenameBody body) {
        moduleService.renameGroup(body.getApplicationId(), body.getFrom(), body.getTo());
        return ApiResponse.ok();
    }

    @PutMapping("/modules/{id}/group")
    public ApiResponse<Void> changeGroup(@PathVariable Long id, @RequestBody GroupBody body) {
        moduleService.changeGroup(id, body.getGroupName());
        return ApiResponse.ok();
    }

    @PutMapping("/modules/sort")
    public ApiResponse<Void> sort(@RequestBody SortBody body) {
        moduleService.sort(body.getIds());
        return ApiResponse.ok();
    }

    @PostMapping("/modules/{id}/copy")
    public ApiResponse<Module> copy(@PathVariable Long id, @RequestBody CopyBody body) {
        return ApiResponse.ok(moduleService.copy(id, body.getTargetAppId(),
                Boolean.TRUE.equals(body.getWithValues())));
    }

    // ---- 订阅 ----

    @PostMapping("/applications/{appId}/subscriptions")
    public ApiResponse<ModuleSubscription> subscribe(@PathVariable Long appId,
                                                     @RequestBody SubscribeBody body) {
        return ApiResponse.ok(moduleService.subscribe(appId, body.getModuleId()));
    }

    @DeleteMapping("/applications/{appId}/subscriptions/{moduleId}")
    public ApiResponse<Void> unsubscribe(@PathVariable Long appId, @PathVariable Long moduleId) {
        moduleService.unsubscribe(appId, moduleId);
        return ApiResponse.ok();
    }

    @GetMapping("/modules/{id}/environment-ids")
    public ApiResponse<List<Long>> environmentIds(@PathVariable Long id) {
        moduleService.requireById(id);
        return ApiResponse.ok(moduleService.environmentIdsOf(id));
    }

    // ---- DTO ----

    @Data
    public static class CreateBody {
        private String code;
        private String name;
        private String description;
        private String groupName;
        private Integer sortOrder;
        private Long templateId;
        private List<Long> environmentIds;

        Module toModule() {
            Module m = new Module();
            m.setCode(code);
            m.setName(name);
            m.setDescription(description);
            m.setGroupName(groupName);
            m.setSortOrder(sortOrder);
            m.setTemplateId(templateId);
            return m;
        }
    }

    @Data
    public static class UpdateBody {
        private String code;
        private String name;
        private String description;
        private String groupName;
        private Integer sortOrder;
        private List<Long> environmentIds;

        Module toModule() {
            Module m = new Module();
            m.setCode(code);
            m.setName(name);
            m.setDescription(description);
            m.setGroupName(groupName);
            m.setSortOrder(sortOrder);
            return m;
        }
    }

    @Data
    public static class GroupRenameBody {
        private Long applicationId;
        private String from;
        private String to;
    }

    @Data
    public static class GroupBody {
        private String groupName;
    }

    @Data
    public static class SortBody {
        private List<Long> ids;
    }

    @Data
    public static class CopyBody {
        private Long targetAppId;
        private Boolean withValues;
    }

    @Data
    public static class SubscribeBody {
        private Long moduleId;
    }

    @Data
    public static class ModuleListVO {
        private Long id;
        private String code;
        private String name;
        private String description;
        private Boolean isShared;
        private String groupName;
        private Long applicationId;
        private String applicationCode;
        private int variableCount;
        private int environmentCount;
        private Instant updatedAt;
    }

    @Data
    public static class ModuleDetail {
        private Long id;
        private String code;
        private String name;
        private String description;
        private String groupName;
        private Boolean isShared;
        private Long applicationId;
        private String applicationCode;
        private String applicationName;
        private Instant updatedAt;
        private Long templateId;
        private Instant templateSyncedAt;
        private List<Long> environmentIds;

        static ModuleDetail of(Module m, ModuleService svc) {
            ModuleDetail d = new ModuleDetail();
            d.setId(m.getId());
            d.setCode(m.getCode());
            d.setName(m.getName());
            d.setDescription(m.getDescription());
            d.setGroupName(m.getGroupName());
            d.setIsShared(m.getIsShared());
            d.setApplicationId(m.getApplicationId());
            d.setTemplateId(m.getTemplateId());
            d.setTemplateSyncedAt(m.getTemplateSyncedAt());
            d.setEnvironmentIds(svc.environmentIdsOf(m.getId()));
            return d;
        }
    }
}
