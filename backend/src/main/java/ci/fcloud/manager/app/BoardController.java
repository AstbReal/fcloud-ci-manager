package ci.fcloud.manager.app;

import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.module.Module;
import ci.fcloud.manager.module.ModuleEnv;
import ci.fcloud.manager.module.ModuleEnvMapper;
import ci.fcloud.manager.module.ModuleMapper;
import ci.fcloud.manager.module.ModuleSubscription;
import ci.fcloud.manager.module.ModuleSubscriptionMapper;
import ci.fcloud.manager.module.ResolvedValue;
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
import java.util.List;

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

        List<BoardApp> appCards = new ArrayList<>();
        for (Application a : apps) {
            BoardApp card = new BoardApp();
            card.setId(a.getId());
            card.setCode(a.getCode());
            card.setName(a.getName());
            card.setDescription(a.getDescription());
            card.setSortOrder(a.getSortOrder());
            card.setPrivateModules(moduleMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Module>()
                                    .eq(Module::getApplicationId, a.getId())
                                    .orderByAsc(Module::getSortOrder)
                                    .orderByAsc(Module::getId))
                    .stream().map(m -> cardOf(m, a.getId())).toList());
            // 订阅的共享模块
            List<ModuleSubscription> subs = subscriptionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleSubscription>()
                            .eq(ModuleSubscription::getApplicationId, a.getId()));
            List<ModuleCard> shared = new ArrayList<>();
            for (ModuleSubscription s : subs) {
                Module m = moduleMapper.selectById(s.getModuleId());
                if (m != null) {
                    shared.add(cardOf(m, a.getId()));
                }
            }
            card.setSubscribedModules(shared);
            appCards.add(card);
        }
        board.setApplications(appCards);

        // 共享模块库（含订阅数）
        List<Module> sharedModules = moduleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Module>()
                        .eq(Module::getIsShared, true)
                        .orderByAsc(Module::getSortOrder)
                        .orderByAsc(Module::getId));
        List<SharedModuleCard> sharedCards = new ArrayList<>();
        for (Module m : sharedModules) {
            SharedModuleCard c = new SharedModuleCard();
            c.setId(m.getId());
            c.setCode(m.getCode());
            c.setName(m.getName());
            c.setDescription(m.getDescription());
            c.setSubscriptionCount(
                    subscriptionMapper.selectCount(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleSubscription>()
                                    .eq(ModuleSubscription::getModuleId, m.getId())).intValue());
            sharedCards.add(c);
        }
        board.setSharedModules(sharedCards);
        return ApiResponse.ok(board);
    }

    @GetMapping("/applications")
    public ApiResponse<List<Application>> list() {
        return ApiResponse.ok(applicationService.listAll());
    }

    /**
     * 模块卡片 + 健康度。共享模块按"其在当前应用下的解析结果"计算，
     * 因此同一共享模块在不同应用卡片上的健康度可能不同（这是正确的）。
     */
    private ModuleCard cardOf(Module m, Long appId) {
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
        c.setHealth(healthOf(m, appId));
        return c;
    }

    private Health healthOf(Module m, Long appId) {
        Health h = new Health();
        List<Long> bound = moduleEnvMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                                .eq(ModuleEnv::getModuleId, m.getId()))
                .stream().map(ModuleEnv::getEnvironmentId).toList();
        if (!bound.isEmpty()) {
            List<Variable> required = variableMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                            .eq(Variable::getModuleId, m.getId())
                            .eq(Variable::getIsRequired, true));
            for (Long envId : bound) {
                for (Variable v : required) {
                    ResolvedValue rv = variableResolver.resolveVariable(
                            v, appId, envId, false);
                    if (!rv.isFound()) {
                        h.setMissingRequired(h.getMissingRequired() + 1);
                    }
                }
            }
        }
        if (m.getTemplateId() != null) {
            Template t = templateMapper.selectById(m.getTemplateId());
            Instant updated = t == null ? null : t.getUpdatedAt();
            h.setTemplatePending(updated != null
                    && (m.getTemplateSyncedAt() == null || updated.isAfter(m.getTemplateSyncedAt())));
        }
        return h;
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
