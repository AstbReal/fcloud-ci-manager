package ci.fcloud.manager.module;

import ci.fcloud.manager.app.Application;
import ci.fcloud.manager.app.ApplicationMapper;
import ci.fcloud.manager.app.ApplicationService;
import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.template.TemplateService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final TemplateService templateService;

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
        if (modules.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        // 批量聚合，避免逐模块 selectCount 造成的 N+1（182 个模块曾是 364 条 SQL / ~900ms）
        Map<Long, Application> apps = new HashMap<>();
        for (Application a : applicationMapper.selectList(null)) {
            apps.put(a.getId(), a);
        }
        Map<Long, Integer> varCounts = countByModuleId(variableMapper);
        Map<Long, Integer> envCounts = countByModuleId(moduleEnvMapper);

        List<ModuleListVO> out = new ArrayList<>(modules.size());
        for (Module m : modules) {
            ModuleListVO vo = new ModuleListVO();
            vo.setId(m.getId());
            vo.setCode(m.getCode());
            vo.setName(m.getName());
            vo.setDescription(m.getDescription());
            vo.setIsShared(m.getIsShared());
            vo.setGroupName(m.getGroupName());
            vo.setApplicationId(m.getApplicationId());
            Application app = m.getApplicationId() == null ? null : apps.get(m.getApplicationId());
            vo.setApplicationCode(app == null ? null : app.getCode());
            vo.setApplicationName(app == null ? null : app.getName());
            vo.setUpdatedAt(m.getUpdatedAt());
            vo.setVariableCount(varCounts.getOrDefault(m.getId(), 0));
            vo.setEnvironmentCount(envCounts.getOrDefault(m.getId(), 0));
            out.add(vo);
        }
        return ApiResponse.ok(out);
    }

    /** 一条 GROUP BY 拿全部模块的计数，替代逐行 selectCount。 */
    private static <T> Map<Long, Integer> countByModuleId(BaseMapper<T> mapper) {
        QueryWrapper<T> qw = new QueryWrapper<>();
        qw.select("module_id", "count(*) as cnt").groupBy("module_id");
        Map<Long, Integer> out = new HashMap<>();
        for (Map<String, Object> row : mapper.selectMaps(qw)) {
            Long moduleId = null;
            Integer cnt = null;
            // MyBatis 对 Map 结果的列名大小写/下划线处理不统一，这里按键名归一化后匹配
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String k = e.getKey().toLowerCase().replace("_", "");
                if ("moduleid".equals(k) && e.getValue() instanceof Number n) {
                    moduleId = n.longValue();
                } else if ("cnt".equals(k) && e.getValue() instanceof Number n) {
                    cnt = n.intValue();
                }
            }
            if (moduleId != null && cnt != null) {
                out.put(moduleId, cnt);
            }
        }
        return out;
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
        Module m = moduleService.update(id, body.toModule(), body.getEnvironmentIds());
        if (body.getEnvironmentIds() != null) {
            // 新绑定的环境要补发模板文件；已有环境内容不变（sha 相同不产生新版本）
            templateService.materializeFilesIfBound(id);
        }
        return ApiResponse.ok(m);
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

    /**
     * 批量订阅：一个模块 × 一批应用。
     *
     * <p>放在 modules 下面是刻意的 —— 批量操作的主体是「模块的订阅方集合」，
     * 应用侧的单条接口继续服务「在某个应用里勾选模块」的场景。
     */
    @PostMapping("/modules/{id}/subscriptions")
    public ApiResponse<ModuleService.BatchResult> subscribeBatch(
            @PathVariable Long id, @RequestBody SubscriptionBatchBody body) {
        return ApiResponse.ok(moduleService.subscribeBatch(id, body.getAppIds()));
    }

    /**
     * 批量取消订阅。DELETE 带 JSON body：ids 可能上百个，走 query string 会顶到 URL 长度上限。
     */
    @DeleteMapping("/modules/{id}/subscriptions")
    public ApiResponse<ModuleService.BatchResult> unsubscribeBatch(
            @PathVariable Long id, @RequestBody SubscriptionBatchBody body) {
        return ApiResponse.ok(moduleService.unsubscribeBatch(id, body.getAppIds()));
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
    public static class SubscriptionBatchBody {
        /** 应用 id 列表；后端会去重、去空，重复提交按幂等处理 */
        private List<Long> appIds;
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
        private String applicationName;
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
