package ci.fcloud.hub.app;

import ci.fcloud.hub.audit.AuditLog;
import ci.fcloud.hub.audit.AuditLogMapper;
import ci.fcloud.hub.common.ApiResponse;
import ci.fcloud.hub.common.PageResult;
import ci.fcloud.hub.common.SecurityUtils;
import ci.fcloud.hub.env.Environment;
import ci.fcloud.hub.env.EnvironmentMapper;
import ci.fcloud.hub.module.*;
import ci.fcloud.hub.token.ApiToken;
import ci.fcloud.hub.token.ApiTokenMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * §9.3 工作台待办聚合（登录可读）。
 *
 * 四类待办：
 * 1. 缺必填变量的「模块 × 环境」
 * 2. 模板待同步的模块
 * 3. 7 天内到期的 API Token（仅系统管理员可见）
 * 4. 最近 24h 高危操作（仅系统管理员可见）
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

    @GetMapping("/todos")
    public ApiResponse<Todos> todos() {
        Todos out = new Todos();
        boolean admin = SecurityUtils.isSystemAdmin();

        List<Environment> envs = environmentMapper.selectList(null);
        List<ci.fcloud.hub.module.Module> modules = moduleMapper.selectList(null);

        // 1) 缺必填变量：模块绑定的每个环境下，必填变量都应有值
        for (ci.fcloud.hub.module.Module m : modules) {
            List<Long> bound = moduleEnvMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                                    .eq(ModuleEnv::getModuleId, m.getId()))
                    .stream().map(ModuleEnv::getEnvironmentId).toList();
            if (bound.isEmpty()) {
                continue;
            }
            List<Variable> required = variableMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                            .eq(Variable::getModuleId, m.getId())
                            .eq(Variable::getIsRequired, true));
            if (required.isEmpty()) {
                continue;
            }
            for (Long envId : bound) {
                List<String> missing = new ArrayList<>();
                for (Variable v : required) {
                    ResolvedValue rv = variableResolver.resolveVariable(
                            v, m.getApplicationId(), envId, false);
                    if (!rv.isFound()) {
                        missing.add(v.getVarKey());
                    }
                }
                if (!missing.isEmpty()) {
                    Environment env = envs.stream()
                            .filter(e -> e.getId().equals(envId)).findFirst().orElse(null);
                    MissingRequired mr = new MissingRequired();
                    mr.setModuleId(m.getId());
                    mr.setModuleCode(m.getCode());
                    mr.setModuleName(m.getName() + " (" + m.getCode() + ")");
                    mr.setEnvironmentId(envId);
                    mr.setEnvironmentCode(env == null ? null : env.getCode());
                    mr.setEnvironmentName(env == null ? String.valueOf(envId) : env.getName());
                    mr.setMissingKeys(missing);
                    out.missingRequired.add(mr);
                }
            }
        }

        // 2) 模板待同步：template.updated_at > module.template_synced_at
        for (ci.fcloud.hub.module.Module m : modules) {
            if (m.getTemplateId() == null) {
                continue;
            }
            ModuleTemplateInfo info = templateUpdatedAt(m.getTemplateId());
            if (info != null && info.updatedAt() != null
                    && (m.getTemplateSyncedAt() == null
                    || info.updatedAt().isAfter(m.getTemplateSyncedAt()))) {
                TemplateUpdate tu = new TemplateUpdate();
                tu.setModuleId(m.getId());
                tu.setModuleName(m.getName() + " (" + m.getCode() + ")");
                tu.setTemplateId(info.id());
                out.templateUpdates.add(tu);
            }
        }

        if (admin) {
            // 3) 7 天内到期的 Token
            Instant soon = Instant.now().plus(7, ChronoUnit.DAYS);
            List<ApiToken> tokens = tokenMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApiToken>()
                            .eq(ApiToken::getRevoked, false)
                            .isNotNull(ApiToken::getExpiresAt)
                            .le(ApiToken::getExpiresAt, soon)
                            .ge(ApiToken::getExpiresAt, Instant.now()));
            for (ApiToken t : tokens) {
                ExpiringToken et = new ExpiringToken();
                et.setId(t.getId());
                et.setName(t.getName());
                et.setTokenPrefix(t.getTokenPrefix());
                et.setExpiresAt(t.getExpiresAt());
                out.expiringTokens.add(et);
            }

            // 4) 最近 24h 高危操作
            List<AuditLog> critical = auditLogMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuditLog>()
                            .eq(AuditLog::getIsCritical, true)
                            .ge(AuditLog::getCreatedAt, Instant.now().minus(24, ChronoUnit.HOURS))
                            .orderByDesc(AuditLog::getCreatedAt)
                            .last("limit 20"));
            for (AuditLog a : critical) {
                CriticalAudit ca = new CriticalAudit();
                ca.setId(a.getId());
                ca.setAction(a.getAction());
                ca.setUserEmail(a.getUserEmail());
                ca.setCreatedAt(a.getCreatedAt());
                out.criticalAudits.add(ca);
            }
        }
        // 5) 最近更新的模块（工作台底部表格；按 updated_at 倒序）
        List<ci.fcloud.hub.module.Module> recent = moduleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ci.fcloud.hub.module.Module>()
                        .isNotNull(ci.fcloud.hub.module.Module::getUpdatedAt)
                        .orderByDesc(ci.fcloud.hub.module.Module::getUpdatedAt)
                        .last("limit 6"));
        for (ci.fcloud.hub.module.Module m : recent) {
            RecentModule rm = new RecentModule();
            rm.setId(m.getId());
            rm.setCode(m.getCode());
            rm.setName(m.getName());
            rm.setShared(Boolean.TRUE.equals(m.getIsShared()));
            rm.setUpdatedAt(m.getUpdatedAt());
            if (m.getApplicationId() != null) {
                Application app = applicationMapper.selectById(m.getApplicationId());
                if (app != null) {
                    rm.setApplicationCode(app.getCode());
                    rm.setApplicationName(app.getName());
                }
            }
            rm.setVariableCount(variableMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                            .eq(Variable::getModuleId, m.getId())).intValue());
            List<ModuleEnv> binds = moduleEnvMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                            .eq(ModuleEnv::getModuleId, m.getId()));
            rm.setEnvironmentCount(binds.size());
            if (!binds.isEmpty()) {
                List<Long> envIds = binds.stream().map(ModuleEnv::getEnvironmentId).toList();
                List<Environment> bound = environmentMapper.selectBatchIds(envIds);
                bound.sort(java.util.Comparator.comparing(
                        e -> e.getSortOrder() == null ? 0 : e.getSortOrder()));
                rm.setEnvironments(bound.stream().map(Environment::getCode).toList());
            }
            out.recentModules.add(rm);
        }

        return ApiResponse.ok(out);
    }

    // 用 JdbcTemplate 查模板 updated_at，避免与 TemplateService 循环依赖
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private ModuleTemplateInfo templateUpdatedAt(Long templateId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, updated_at FROM fch_template WHERE id = ?",
                    (rs, i) -> new ModuleTemplateInfo(
                            rs.getLong("id"),
                            rs.getTimestamp("updated_at") == null ? null
                                    : rs.getTimestamp("updated_at").toInstant()),
                    templateId);
        } catch (Exception e) {
            return null;
        }
    }

    private record ModuleTemplateInfo(Long id, Instant updatedAt) {
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
