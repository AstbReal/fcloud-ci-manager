package ci.fcloud.manager.app;

import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.module.Module;
import ci.fcloud.manager.module.ModuleEnv;
import ci.fcloud.manager.module.ModuleEnvMapper;
import ci.fcloud.manager.module.ModuleMapper;
import ci.fcloud.manager.module.ModuleSubscription;
import ci.fcloud.manager.module.ModuleSubscriptionMapper;
import ci.fcloud.manager.module.Variable;
import ci.fcloud.manager.module.VariableMapper;
import ci.fcloud.manager.module.VariableResolver;
import ci.fcloud.manager.template.Template;
import ci.fcloud.manager.template.TemplateMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * §5.3 看板数据（登录即可访问；§6.3 模块可见性：管理员可见全部，
 * 其他用户仅可见自己是成员的模块 —— 成员关系在 M4 实现，此处先按登录态返回）。
 *
 * <p>§10.3 健康度：模块级返回 {@code missingRequired}（缺必填项的
 * 「变量 × 环境」对数）与 {@code templatePending}。聚合在后端完成，
 * 前端不逐模块自行推算。</p>
 */
@RestController
@RequestMapping("/api/console")
@RequiredArgsConstructor
public class BoardController {

    private final ApplicationService applicationService;
    private final ModuleMapper moduleMapper;
    private final ModuleSubscriptionMapper subscriptionMapper;
    private final ModuleEnvMapper moduleEnvMapper;
    private final VariableMapper variableMapper;
    private final VariableResolver variableResolver;
    private final TemplateMapper templateMapper;

    @GetMapping("/applications/board")
    public ApiResponse<Board> board() {
        Board board = new Board();
        List<Application> apps = applicationService.listAll();

        // 整块看板的查询数固定（约 8 条），不随应用/模块数量增长。
        // 之前是「每应用查模块 + 每订阅查模块 + 每模块查环境绑定/必填变量/模板 +
        // 每变量每环境跑一次解析」，60 个应用就上千条 SQL、1.6s。
        List<Module> allModules = moduleMapper.selectList(null);
        List<ModuleSubscription> allSubs = subscriptionMapper.selectList(null);

        Map<Long, Module> moduleById = new HashMap<>();
        Map<Long, List<Module>> privateByApp = new HashMap<>();
        List<Module> sharedModules = new ArrayList<>();
        for (Module m : allModules) {
            moduleById.put(m.getId(), m);
            if (Boolean.TRUE.equals(m.getIsShared()) || m.getApplicationId() == null) {
                sharedModules.add(m);
            } else {
                privateByApp.computeIfAbsent(m.getApplicationId(), k -> new ArrayList<>()).add(m);
            }
        }
        Comparator<Module> moduleOrder = Comparator
                .comparingInt((Module m) -> m.getSortOrder() == null ? 0 : m.getSortOrder())
                .thenComparing(Module::getId);
        privateByApp.values().forEach(list -> list.sort(moduleOrder));
        sharedModules.sort(moduleOrder);

        Map<Long, List<ModuleSubscription>> subsByApp = new HashMap<>();
        Map<Long, Integer> subCountByModule = new HashMap<>();
        for (ModuleSubscription s : allSubs) {
            subsByApp.computeIfAbsent(s.getApplicationId(), k -> new ArrayList<>()).add(s);
            subCountByModule.merge(s.getModuleId(), 1, Integer::sum);
        }

        HealthCalculator health = healthCalculator(allModules);

        List<BoardApp> appCards = new ArrayList<>(apps.size());
        for (Application a : apps) {
            BoardApp card = new BoardApp();
            card.setId(a.getId());
            card.setCode(a.getCode());
            card.setName(a.getName());
            card.setDescription(a.getDescription());
            card.setSortOrder(a.getSortOrder());
            card.setRepoUrl(a.getRepoUrl());
            card.setGitlabProjectId(a.getGitlabProjectId());
            card.setPrivateModules(privateByApp.getOrDefault(a.getId(), List.of()).stream()
                    .map(m -> cardOf(m, health.of(m, a.getId()))).toList());
            // 订阅的共享模块：健康度按「在当前应用下的解析结果」算
            List<ModuleCard> shared = new ArrayList<>();
            for (ModuleSubscription s : subsByApp.getOrDefault(a.getId(), List.of())) {
                Module m = moduleById.get(s.getModuleId());
                if (m != null) {
                    shared.add(cardOf(m, health.of(m, a.getId())));
                }
            }
            card.setSubscribedModules(shared);
            appCards.add(card);
        }
        board.setApplications(appCards);

        // 共享模块库（含订阅数）
        List<SharedModuleCard> sharedCards = new ArrayList<>(sharedModules.size());
        for (Module m : sharedModules) {
            SharedModuleCard c = new SharedModuleCard();
            c.setId(m.getId());
            c.setCode(m.getCode());
            c.setName(m.getName());
            c.setDescription(m.getDescription());
            c.setSubscriptionCount(subCountByModule.getOrDefault(m.getId(), 0));
            sharedCards.add(c);
        }
        board.setSharedModules(sharedCards);
        return ApiResponse.ok(board);
    }

    @GetMapping("/applications")
    public ApiResponse<List<Application>> list() {
        return ApiResponse.ok(applicationService.listAll());
    }

    /** 健康度计算所需的批量数据：构造一次，全看板复用，计算过程零查询。 */
    private HealthCalculator healthCalculator(List<Module> modules) {
        List<Long> moduleIds = modules.stream().map(Module::getId).toList();
        Map<Long, List<Long>> boundEnv = new HashMap<>();
        Map<Long, List<Variable>> requiredVars = new HashMap<>();
        if (!moduleIds.isEmpty()) {
            for (ModuleEnv me : moduleEnvMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                            .in(ModuleEnv::getModuleId, moduleIds))) {
                boundEnv.computeIfAbsent(me.getModuleId(), k -> new ArrayList<>())
                        .add(me.getEnvironmentId());
            }
            for (Variable v : variableMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                            .in(Variable::getModuleId, moduleIds)
                            .eq(Variable::getIsRequired, true))) {
                requiredVars.computeIfAbsent(v.getModuleId(), k -> new ArrayList<>()).add(v);
            }
        }
        Map<Long, Template> templates = new HashMap<>();
        List<Long> templateIds = modules.stream().map(Module::getTemplateId)
                .filter(Objects::nonNull).distinct().toList();
        if (!templateIds.isEmpty()) {
            for (Template t : templateMapper.selectBatchIds(templateIds)) {
                templates.put(t.getId(), t);
            }
        }
        VariableResolver.Context ctx = variableResolver.context(
                requiredVars.values().stream().flatMap(List::stream).toList());
        return new HealthCalculator(boundEnv, requiredVars, templates, ctx);
    }

    private static final class HealthCalculator {

        private final Map<Long, List<Long>> boundEnvByModule;
        private final Map<Long, List<Variable>> requiredByModule;
        private final Map<Long, Template> templateById;
        private final VariableResolver.Context ctx;

        private HealthCalculator(Map<Long, List<Long>> boundEnvByModule,
                                 Map<Long, List<Variable>> requiredByModule,
                                 Map<Long, Template> templateById,
                                 VariableResolver.Context ctx) {
            this.boundEnvByModule = boundEnvByModule;
            this.requiredByModule = requiredByModule;
            this.templateById = templateById;
            this.ctx = ctx;
        }

        Health of(Module m, Long appId) {
            Health h = new Health();
            List<Long> envs = boundEnvByModule.getOrDefault(m.getId(), List.of());
            List<Variable> required = requiredByModule.getOrDefault(m.getId(), List.of());
            if (!envs.isEmpty() && !required.isEmpty()) {
                int missing = 0;
                for (Long envId : envs) {
                    for (Variable v : required) {
                        if (!ctx.resolve(v, appId, envId, false).isFound()) {
                            missing++;
                        }
                    }
                }
                h.setMissingRequired(missing);
            }
            if (m.getTemplateId() != null) {
                Template t = templateById.get(m.getTemplateId());
                Instant updated = t == null ? null : t.getUpdatedAt();
                h.setTemplatePending(updated != null
                        && (m.getTemplateSyncedAt() == null
                        || updated.isAfter(m.getTemplateSyncedAt())));
            }
            return h;
        }
    }

    /**
     * 模块卡片 + 健康度。共享模块按"其在当前应用下的解析结果"计算，
     * 因此同一共享模块在不同应用卡片上的健康度可能不同（这是正确的）。
     */
    private ModuleCard cardOf(Module m, Health health) {
        ModuleCard c = new ModuleCard();
        c.setId(m.getId());
        c.setApplicationId(m.getApplicationId());
        c.setCode(m.getCode());
        c.setName(m.getName());
        c.setDescription(m.getDescription());
        c.setIsShared(m.getIsShared());
        c.setGroupName(m.getGroupName());
        c.setTemplateId(m.getTemplateId());
        c.setSortOrder(m.getSortOrder());
        c.setUpdatedAt(m.getUpdatedAt());
        c.setHealth(health);
        return c;
    }

    @Data
    public static class Board {
        private List<BoardApp> applications;
        private List<SharedModuleCard> sharedModules;
    }

    @Data
    public static class BoardApp {
        private Long id;
        private String code;
        private String name;
        private String description;
        private Integer sortOrder;
        /** 仓库地址与来源 GitLab 项目；手工创建或未导入时为空 */
        private String repoUrl;
        private Long gitlabProjectId;
        private List<ModuleCard> privateModules;
        private List<ModuleCard> subscribedModules;
    }

    @Data
    public static class ModuleCard {
        private Long id;
        private Long applicationId;
        private String code;
        private String name;
        private String description;
        private Boolean isShared;
        private String groupName;
        private Long templateId;
        private Integer sortOrder;
        private Instant updatedAt;
        private Health health;
    }

    @Data
    public static class Health {
        /** 缺必填项的「变量 × 环境」对数 */
        private int missingRequired;
        /** 关联模板有未同步变更 */
        private boolean templatePending;
    }

    @Data
    public static class SharedModuleCard {
        private Long id;
        private String code;
        private String name;
        private String description;
        private int subscriptionCount;
    }
}
