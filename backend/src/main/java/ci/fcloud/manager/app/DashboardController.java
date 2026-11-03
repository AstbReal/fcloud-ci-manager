package ci.fcloud.manager.app;

import ci.fcloud.manager.audit.AuditLog;
import ci.fcloud.manager.audit.AuditLogMapper;
import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.common.SecurityUtils;
import ci.fcloud.manager.env.Environment;
import ci.fcloud.manager.env.EnvironmentMapper;
// 必须逐个 import：通配符会和 java.lang.Module 冲突（Java 9+），出现「对 Module 的引用不明确」
import ci.fcloud.manager.module.Module;
import ci.fcloud.manager.module.ModuleEnv;
import ci.fcloud.manager.module.ModuleEnvMapper;
import ci.fcloud.manager.module.ModuleMapper;
import ci.fcloud.manager.module.Variable;
import ci.fcloud.manager.module.VariableMapper;
import ci.fcloud.manager.module.VariableResolver;
import ci.fcloud.manager.template.Template;
import ci.fcloud.manager.template.TemplateMapper;
import ci.fcloud.manager.token.ApiToken;
import ci.fcloud.manager.token.ApiTokenMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * §9.3 工作台待办聚合（登录可读）。
 *
 * 四类待办：
 * 1. 缺必填变量的「模块 × 环境」
 * 2. 模板待同步的模块
 * 3. 7 天内到期的 API Token（仅系统管理员可见）
 * 4. 最近 24h 高危操作（仅系统管理员可见）
 *
 * <p>侧栏角标只需要一个数字，走 {@code /todo-count}：数据来源同一套批量加载，
 * 但不再拼装完整列表。{@code /todos} 是工作台页面的数据源。</p>
 */
@RestController
@RequestMapping("/api/console/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final ModuleMapper moduleMapper;
    private final ModuleEnvMapper moduleEnvMapper;
    private final EnvironmentMapper environmentMapper;
    private final VariableMapper variableMapper;
    private final VariableResolver variableResolver;
    private final ApiTokenMapper tokenMapper;
    private final AuditLogMapper auditLogMapper;
    private final ApplicationMapper applicationMapper;
    private final TemplateMapper templateMapper;

    /** 工作台页面用：完整待办列表。 */
    @GetMapping("/todos")
    public ApiResponse<Todos> todos() {
        Snapshot s = snapshot();
        Todos out = new Todos();

        for (MissingHit hit : missingHits(s)) {
            MissingRequired mr = new MissingRequired();
            mr.setModuleId(hit.module().getId());
            mr.setModuleCode(hit.module().getCode());
            mr.setModuleName(nameOf(hit.module()));
            mr.setEnvironmentId(hit.environmentId());
            Environment env = s.envById().get(hit.environmentId());
            mr.setEnvironmentCode(env == null ? null : env.getCode());
            mr.setEnvironmentName(env == null
                    ? String.valueOf(hit.environmentId()) : env.getName());
            mr.setMissingKeys(hit.missingKeys());
            out.missingRequired.add(mr);
        }

        for (Module m : s.modules()) {
            if (m.getTemplateId() == null) {
                continue;
            }
            Template t = s.templateById().get(m.getTemplateId());
            Instant updated = t == null ? null : t.getUpdatedAt();
            if (updated != null && (m.getTemplateSyncedAt() == null
                    || updated.isAfter(m.getTemplateSyncedAt()))) {
                TemplateUpdate tu = new TemplateUpdate();
                tu.setModuleId(m.getId());
                tu.setModuleName(nameOf(m));
                tu.setTemplateId(m.getTemplateId());
                out.templateUpdates.add(tu);
            }
        }

        if (SecurityUtils.isSystemAdmin()) {
            for (ApiToken t : expiringTokens()) {
                ExpiringToken et = new ExpiringToken();
                et.setId(t.getId());
                et.setName(t.getName());
                et.setTokenPrefix(t.getTokenPrefix());
                et.setExpiresAt(t.getExpiresAt());
                out.expiringTokens.add(et);
            }
            for (AuditLog a : criticalAudits()) {
                CriticalAudit ca = new CriticalAudit();
                ca.setId(a.getId());
                ca.setAction(a.getAction());
                ca.setUserEmail(a.getUserEmail());
                ca.setCreatedAt(a.getCreatedAt());
                out.criticalAudits.add(ca);
            }
        }

        // 最近更新的模块（工作台底部表格；按 updated_at 倒序）
        out.recentModules.addAll(recentModules(s));
        return ApiResponse.ok(out);
    }

    /**
     * 侧栏角标用：只返回四个数字。
     *
     * <p>之前外壳直接调 /todos，等于每次进任意页面都跑一遍完整聚合（含缺值解析），
     * 63 个应用下要 1.6s、返回 2KB 却只用其中三个数组的长度。</p>
     */
    @GetMapping("/todo-count")
    public ApiResponse<TodoCount> todoCount() {
        Snapshot s = snapshot();
        TodoCount out = new TodoCount();
        out.setMissingRequired(missingHits(s).size());
        int templateUpdates = 0;
        for (Module m : s.modules()) {
            if (m.getTemplateId() == null) {
                continue;
            }
            Template t = s.templateById().get(m.getTemplateId());
            Instant updated = t == null ? null : t.getUpdatedAt();
            if (updated != null && (m.getTemplateSyncedAt() == null
                    || updated.isAfter(m.getTemplateSyncedAt()))) {
                templateUpdates++;
            }
        }
        out.setTemplateUpdates(templateUpdates);
        out.setExpiringTokens(SecurityUtils.isSystemAdmin() ? expiringTokens().size() : 0);
        return ApiResponse.ok(out);
    }

    // ---------------- 批量加载 ----------------

    /**
     * 一次把待办要用的数据全读出来。之前是「每模块查环境绑定 + 每模块查必填变量 +
     * 每变量每环境跑一次解析」，模块一多就是上千条 SQL。
     */
    private Snapshot snapshot() {
        List<Module> modules = moduleMapper.selectList(null);
        List<Environment> envs = environmentMapper.selectList(null);
        Map<Long, Environment> envById = new HashMap<>();
        for (Environment e : envs) {
            envById.put(e.getId(), e);
        }

        List<Long> moduleIds = modules.stream().map(Module::getId).toList();
        Map<Long, List<Long>> boundByModule = new HashMap<>();
        Map<Long, List<Variable>> requiredByModule = new HashMap<>();
        if (!moduleIds.isEmpty()) {
            for (ModuleEnv me : moduleEnvMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                            .in(ModuleEnv::getModuleId, moduleIds))) {
                boundByModule.computeIfAbsent(me.getModuleId(), k -> new ArrayList<>())
                        .add(me.getEnvironmentId());
            }
            for (Variable v : variableMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                            .in(Variable::getModuleId, moduleIds)
                            .eq(Variable::getIsRequired, true))) {
                requiredByModule.computeIfAbsent(v.getModuleId(), k -> new ArrayList<>()).add(v);
            }
        }

        Map<Long, Template> templateById = new HashMap<>();
        List<Long> templateIds = modules.stream().map(Module::getTemplateId)
                .filter(Objects::nonNull).distinct().toList();
        if (!templateIds.isEmpty()) {
            for (Template t : templateMapper.selectBatchIds(templateIds)) {
                templateById.put(t.getId(), t);
            }
        }

        VariableResolver.Context ctx = variableResolver.context(
                requiredByModule.values().stream().flatMap(List::stream).toList());
        return new Snapshot(modules, envById, boundByModule, requiredByModule, templateById, ctx);
    }

    /** 缺必填的「模块 × 环境」命中项。解析全在内存完成。 */
    private List<MissingHit> missingHits(Snapshot s) {
        List<MissingHit> hits = new ArrayList<>();
        for (Module m : s.modules()) {
            List<Long> bound = s.boundByModule().getOrDefault(m.getId(), List.of());
            List<Variable> required = s.requiredByModule().getOrDefault(m.getId(), List.of());
            if (bound.isEmpty() || required.isEmpty()) {
                continue;
            }
            for (Long envId : bound) {
                List<String> missing = new ArrayList<>();
                for (Variable v : required) {
                    if (!s.ctx().resolve(v, m.getApplicationId(), envId, false).isFound()) {
                        missing.add(v.getVarKey());
                    }
                }
                if (!missing.isEmpty()) {
                    hits.add(new MissingHit(m, envId, missing));
                }
            }
        }
        return hits;
    }

    private List<ApiToken> expiringTokens() {
        Instant now = Instant.now();
        return tokenMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApiToken>()
                        .eq(ApiToken::getRevoked, false)
                        .isNotNull(ApiToken::getExpiresAt)
                        .le(ApiToken::getExpiresAt, now.plus(7, ChronoUnit.DAYS))
                        .ge(ApiToken::getExpiresAt, now));
    }

    private List<AuditLog> criticalAudits() {
        return auditLogMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditLog>()
                        .eq(AuditLog::getIsCritical, true)
                        .ge(AuditLog::getCreatedAt, Instant.now().minus(24, ChronoUnit.HOURS))
                        .orderByDesc(AuditLog::getCreatedAt)
                        .last("limit 20"));
    }

    /** 最近更新的 6 个模块：变量数用一条 GROUP BY，应用名用一次全表（应用表很小）。 */
    private List<RecentModule> recentModules(Snapshot s) {
        List<Module> recent = moduleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Module>()
                        .isNotNull(Module::getUpdatedAt)
                        .orderByDesc(Module::getUpdatedAt)
                        .last("limit 6"));
        if (recent.isEmpty()) {
            return List.of();
        }
        List<Long> ids = recent.stream().map(Module::getId).toList();
        Map<Long, Integer> varCount = new HashMap<>();
        for (Map<String, Object> row : variableMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Variable>()
                        .select("module_id", "count(*) as cnt")
                        .in("module_id", ids)
                        .groupBy("module_id"))) {
            Long mid = null;
            Integer cnt = null;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String k = e.getKey().toLowerCase().replace("_", "");
                if ("moduleid".equals(k) && e.getValue() instanceof Number n) {
                    mid = n.longValue();
                } else if ("cnt".equals(k) && e.getValue() instanceof Number n) {
                    cnt = n.intValue();
                }
            }
            if (mid != null && cnt != null) {
                varCount.put(mid, cnt);
            }
        }
        Map<Long, Application> apps = new HashMap<>();
        for (Application a : applicationMapper.selectList(null)) {
            apps.put(a.getId(), a);
        }

        List<RecentModule> out = new ArrayList<>(recent.size());
        for (Module m : recent) {
            RecentModule rm = new RecentModule();
            rm.setId(m.getId());
            rm.setCode(m.getCode());
            rm.setName(m.getName());
            rm.setShared(Boolean.TRUE.equals(m.getIsShared()));
            rm.setUpdatedAt(m.getUpdatedAt());
            Application app = m.getApplicationId() == null ? null : apps.get(m.getApplicationId());
            if (app != null) {
                rm.setApplicationCode(app.getCode());
                rm.setApplicationName(app.getName());
            }
            rm.setVariableCount(varCount.getOrDefault(m.getId(), 0));
            List<Long> envIds = s.boundByModule().getOrDefault(m.getId(), List.of());
            rm.setEnvironmentCount(envIds.size());
            if (!envIds.isEmpty()) {
                List<Environment> bound = new ArrayList<>();
                for (Long envId : envIds) {
                    Environment e = s.envById().get(envId);
                    if (e != null) {
                        bound.add(e);
                    }
                }
                bound.sort(Comparator.comparingInt(
                        e -> e.getSortOrder() == null ? 0 : e.getSortOrder()));
                rm.setEnvironments(bound.stream().map(Environment::getCode).toList());
            }
            out.add(rm);
        }
        return out;
    }

    private static String nameOf(Module m) {
        return m.getName() + " (" + m.getCode() + ")";
    }

    private record Snapshot(List<Module> modules,
                            Map<Long, Environment> envById,
                            Map<Long, List<Long>> boundByModule,
                            Map<Long, List<Variable>> requiredByModule,
                            Map<Long, Template> templateById,
                            VariableResolver.Context ctx) {
    }

    private record MissingHit(Module module, Long environmentId, List<String> missingKeys) {
    }

    @Data
    public static class Todos {
        private List<MissingRequired> missingRequired = new ArrayList<>();
        private List<TemplateUpdate> templateUpdates = new ArrayList<>();
        private List<ExpiringToken> expiringTokens = new ArrayList<>();
        private List<CriticalAudit> criticalAudits = new ArrayList<>();
        private List<RecentModule> recentModules = new ArrayList<>();
    }

    @Data
    public static class TodoCount {
        /** 缺必填的「模块 × 环境」条数 */
        private int missingRequired;
        /** 模板待同步的模块数 */
        private int templateUpdates;
        /** 7 天内到期的 Token 数（非管理员恒为 0） */
        private int expiringTokens;
    }

    @Data
    public static class RecentModule {
        private Long id;
        private String code;
        private String name;
        private Boolean shared;
        private String applicationCode;
        private String applicationName;
        private int variableCount;
        private int environmentCount;
        private java.util.List<String> environments;
        private Instant updatedAt;
    }

    @Data
    public static class MissingRequired {
        private Long moduleId;
        private String moduleCode;
        private String moduleName;
        private Long environmentId;
        private String environmentCode;
        private String environmentName;
        private List<String> missingKeys;
    }

    @Data
    public static class TemplateUpdate {
        private Long moduleId;
        private String moduleName;
        private Long templateId;
    }

    @Data
    public static class ExpiringToken {
        private Long id;
        private String name;
        private String tokenPrefix;
        private Instant expiresAt;
    }

    @Data
    public static class CriticalAudit {
        private Long id;
        private String action;
        private String userEmail;
        private Instant createdAt;
    }
}
