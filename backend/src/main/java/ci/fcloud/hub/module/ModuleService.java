package ci.fcloud.hub.module;

import ci.fcloud.hub.app.Application;
import ci.fcloud.hub.app.ApplicationMapper;
import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.common.SecurityUtils;
import ci.fcloud.hub.env.Environment;
import ci.fcloud.hub.env.EnvironmentMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * §5.4 模块 CRUD + 绑定环境 + 共享模块 + 订阅。
 */
@Service
@RequiredArgsConstructor
public class ModuleService extends ServiceImpl<ModuleMapper, Module> {

    private final ApplicationMapper applicationMapper;
    private final EnvironmentMapper environmentMapper;
    private final ModuleEnvMapper moduleEnvMapper;
    private final ModuleSubscriptionMapper subscriptionMapper;
    private final VariableMapper variableMapper;
    private final VariableOverrideMapper variableOverrideMapper;

    public Module requireById(Long id) {
        Module m = getById(id);
        if (m == null) {
            throw BizException.notFound("模块不存在: " + id);
        }
        return m;
    }

    public Module requireByCode(String code) {
        Module m = lambdaQuery().eq(Module::getCode, code).one();
        if (m == null) {
            throw BizException.badRequest("模块不存在: " + code);
        }
        return m;
    }

    // ---------------- 私有模块 ----------------

    /**
     * §5.4 建私有模块（可带 templateId 实例化）。
     */
    @Transactional
    public Module createPrivate(Long appId, Module body, List<Long> environmentIds) {
        Application app = applicationMapper.selectById(appId);
        if (app == null) {
            throw BizException.badRequest("应用不存在: " + appId);
        }
        validateCode(body, appId);
        Module m = new Module();
        m.setApplicationId(appId);
        m.setCode(body.getCode());
        m.setName(body.getName());
        m.setDescription(body.getDescription());
        m.setIsShared(false);
        m.setGroupName(body.getGroupName());
        m.setSortOrder(body.getSortOrder() == null ? 0 : body.getSortOrder());
        m.setCreatedBy(SecurityUtils.currentUserId());
        save(m);
        bindEnvironments(m.getId(), environmentIds);
        return m;
    }

    // ---------------- 共享模块 ----------------

    /**
     * §5.4 建共享模块（application_id 为空，多个应用可订阅）。
     */
    @Transactional
    public Module createShared(Module body, List<Long> environmentIds) {
        validateCode(body, null);
        Module m = new Module();
        m.setApplicationId(null);
        m.setCode(body.getCode());
        m.setName(body.getName());
        m.setDescription(body.getDescription());
        m.setIsShared(true);
        m.setSortOrder(body.getSortOrder() == null ? 0 : body.getSortOrder());
        m.setCreatedBy(SecurityUtils.currentUserId());
        save(m);
        bindEnvironments(m.getId(), environmentIds);
        return m;
    }

    /**
     * 重命名 / 解散模块分组。applicationId 非空时只改该应用下的模块。
     * to 为空表示移回「未分组」。
     */
    public int renameGroup(Long applicationId, String from, String to) {
        if (from == null || from.isBlank()) {
            throw BizException.badRequest("分组名不能为空");
        }
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Module> w =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Module>()
                        .eq(Module::getGroupName, from)
                        .set(Module::getGroupName, to == null || to.isBlank() ? null : to);
        if (applicationId != null) {
            w.eq(Module::getApplicationId, applicationId);
        }
        update(w);
        return 1;
    }

    public Module update(Long id, Module body, List<Long> environmentIds) {
        Module m = requireById(id);
        if (body.getCode() != null && !body.getCode().equals(m.getCode())) {
            validateCode(body, m.getApplicationId());
            m.setCode(body.getCode());
        }
        if (body.getName() != null) {
            m.setName(body.getName());
        }
        if (body.getDescription() != null) {
            m.setDescription(body.getDescription());
        }
        if (body.getGroupName() != null) {
            m.setGroupName(body.getGroupName());
        }
        if (body.getSortOrder() != null) {
            m.setSortOrder(body.getSortOrder());
        }
        updateById(m);
        if (environmentIds != null) {
            rebindEnvironments(id, environmentIds);
        }
        return m;
    }

    /**
     * §5.4 删除模块；共享模块返回订阅方列表（由 controller 决定是否阻断）。
     */
    @Transactional
    public void delete(Long id) {
        Module m = requireById(id);
        if (Boolean.TRUE.equals(m.getIsShared())) {
            List<ModuleSubscription> subs = subscriptionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleSubscription>()
                            .eq(ModuleSubscription::getModuleId, id));
            if (!subs.isEmpty()) {
                throw new BizException(40900,
                        "共享模块仍有 " + subs.size() + " 个订阅方，删除前请先取消订阅");
            }
        }
        removeById(id);
    }

    public List<Application> subscribersOf(Long sharedModuleId) {
        Module m = requireById(sharedModuleId);
        if (!Boolean.TRUE.equals(m.getIsShared())) {
            throw BizException.badRequest("仅共享模块有订阅方");
        }
        List<ModuleSubscription> subs = subscriptionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleSubscription>()
                        .eq(ModuleSubscription::getModuleId, sharedModuleId));
        return subs.stream()
                .map(s -> applicationMapper.selectById(s.getApplicationId()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    // ---------------- 分组 / 排序 / 复制 ----------------

    public void changeGroup(Long id, String groupName) {
        requireById(id);
        lambdaUpdate().eq(Module::getId, id)
                .set(Module::getGroupName, groupName).update();
    }

    public void sort(List<Long> ids) {
        if (ids == null) {
            return;
        }
        int order = 0;
        for (Long id : ids) {
            lambdaUpdate().eq(Module::getId, id)
                    .set(Module::getSortOrder, order++).update();
        }
    }

    /**
     * §5.4 跨应用复制模块：仅骨架 / 连同值。
     */
    @Transactional
    public Module copy(Long sourceModuleId, Long targetAppId, boolean withValues) {
        Module src = requireById(sourceModuleId);
        Application target = applicationMapper.selectById(targetAppId);
        if (target == null) {
            throw BizException.badRequest("目标应用不存在: " + targetAppId);
        }
        // 模块 code 在同一应用内需唯一，nextAvailableCode 会补 -copy 后缀
        String newCode = nextAvailableCode(src.getCode(), targetAppId);
        Module copy = new Module();
        copy.setApplicationId(targetAppId);
        copy.setCode(newCode);
        copy.setName(src.getName());
        copy.setDescription(src.getDescription());
        copy.setIsShared(false);
        copy.setGroupName(src.getGroupName());
        copy.setSortOrder(0);
        copy.setCreatedBy(SecurityUtils.currentUserId());
        save(copy);

        // 复制环境绑定
        List<ModuleEnv> envs = moduleEnvMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                        .eq(ModuleEnv::getModuleId, sourceModuleId));
        for (ModuleEnv e : envs) {
            ModuleEnv ne = new ModuleEnv();
            ne.setModuleId(copy.getId());
            ne.setEnvironmentId(e.getEnvironmentId());
            moduleEnvMapper.insert(ne);
        }

        // §5.4「仅骨架 / 连同值」：两种模式都复制变量定义（key/类型/备注/必填/顺序），
        // 区别只在于是否带上 global_value 与变量覆盖。
        copyVariables(src.getId(), copy.getId(), withValues);
        return copy;
    }

    /**
     * @param withValues true = 连同值（复制 global_value 与全部覆盖）；
     *                   false = 仅骨架（只复制定义，值全部留空）
     */
    private void copyVariables(Long fromModuleId, Long toModuleId, boolean withValues) {
        List<Variable> vars = variableMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                        .eq(Variable::getModuleId, fromModuleId)
                        .orderByAsc(Variable::getSortOrder));
        for (Variable v : vars) {
            Variable nv = new Variable();
            nv.setModuleId(toModuleId);
            nv.setVarKey(v.getVarKey());
            nv.setValueType(v.getValueType());
            nv.setRemark(v.getRemark());
            nv.setIsRequired(v.getIsRequired());
            nv.setSortOrder(v.getSortOrder());
            nv.setCreatedBy(SecurityUtils.currentUserId());
            if (withValues) {
                nv.setGlobalValue(v.getGlobalValue());
            }
            variableMapper.insert(nv);

            if (withValues) {
                copyOverrides(v.getId(), nv.getId());
            }
        }
    }

    /**
     * 复制变量的环境级/应用级覆盖（仅 withValues 模式）。
     * 应用级覆盖的目标应用是"源应用"，复制到新应用后语义不成立，
     * 因此只复制模块级覆盖（application_id 为空）。
     */
    private void copyOverrides(Long fromVarId, Long toVarId) {
        List<VariableOverride> overrides = variableOverrideMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VariableOverride>()
                        .eq(VariableOverride::getVariableId, fromVarId)
                        .isNull(VariableOverride::getApplicationId));
        for (VariableOverride o : overrides) {
            VariableOverride no = new VariableOverride();
            no.setVariableId(toVarId);
            no.setEnvironmentId(o.getEnvironmentId());
            no.setApplicationId(null);
            no.setValue(o.getValue());
            no.setUpdatedBy(SecurityUtils.currentUserId());
            variableOverrideMapper.insert(no);
        }
    }

    private String nextAvailableCode(String base, Long appId) {
        String candidate = base;
        int i = 1;
        while (existsCode(candidate, appId)) {
            candidate = base + "-copy" + (i++);
        }
        return candidate;
    }

    private boolean existsCode(String code, Long appId) {
        return lambdaQuery().eq(Module::getCode, code)
                .eq(appId != null, Module::getApplicationId, appId)
                .count() > 0;
    }

    // ---------------- 订阅 ----------------

    /**
     * §5.4 订阅共享模块（引用制，实时跟随源模块）。
     */
    public ModuleSubscription subscribe(Long appId, Long sharedModuleId) {
        Application app = applicationMapper.selectById(appId);
        if (app == null) {
            throw BizException.badRequest("应用不存在: " + appId);
        }
        Module m = requireById(sharedModuleId);
        if (!Boolean.TRUE.equals(m.getIsShared())) {
            throw BizException.badRequest("只能订阅共享模块: " + m.getCode());
        }
        long dup = subscriptionMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleSubscription>()
                        .eq(ModuleSubscription::getModuleId, sharedModuleId)
                        .eq(ModuleSubscription::getApplicationId, appId));
        if (dup > 0) {
            throw BizException.conflict("该应用已订阅此共享模块");
        }
        ModuleSubscription s = new ModuleSubscription();
        s.setModuleId(sharedModuleId);
        s.setApplicationId(appId);
        s.setCreatedBy(SecurityUtils.currentUserId());
        subscriptionMapper.insert(s);
        return s;
    }

    /**
     * §5.4 取消订阅（可撤销）。
     */
    public void unsubscribe(Long appId, Long sharedModuleId) {
        int n = subscriptionMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleSubscription>()
                        .eq(ModuleSubscription::getModuleId, sharedModuleId)
                        .eq(ModuleSubscription::getApplicationId, appId));
        if (n == 0) {
            throw BizException.notFound("订阅关系不存在");
        }
    }

    public List<Module> listByApplication(Long appId) {
        return lambdaQuery().eq(Module::getApplicationId, appId)
                .orderByAsc(Module::getSortOrder)
                .orderByAsc(Module::getId)
                .list();
    }

    public List<Module> listShared() {
        return lambdaQuery().eq(Module::getIsShared, true)
                .orderByAsc(Module::getSortOrder)
                .orderByAsc(Module::getId)
                .list();
    }

    // ---------------- 环境绑定 ----------------

    @Transactional
    public void bindEnvironments(Long moduleId, List<Long> environmentIds) {
        if (environmentIds == null) {
            return;
        }
        Set<Long> unique = new HashSet<>(environmentIds);
        for (Long envId : unique) {
            Environment env = environmentMapper.selectById(envId);
            if (env == null) {
                throw BizException.badRequest("环境不存在: " + envId);
            }
            long dup = moduleEnvMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                            .eq(ModuleEnv::getModuleId, moduleId)
                            .eq(ModuleEnv::getEnvironmentId, envId));
            if (dup == 0) {
                ModuleEnv me = new ModuleEnv();
                me.setModuleId(moduleId);
                me.setEnvironmentId(envId);
                moduleEnvMapper.insert(me);
            }
        }
    }

    @Transactional
    public void rebindEnvironments(Long moduleId, List<Long> environmentIds) {
        moduleEnvMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                        .eq(ModuleEnv::getModuleId, moduleId));
        bindEnvironments(moduleId, environmentIds);
    }

    public List<Long> environmentIdsOf(Long moduleId) {
        return moduleEnvMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                                .eq(ModuleEnv::getModuleId, moduleId))
                .stream().map(ModuleEnv::getEnvironmentId).toList();
    }

    // ---------------- 校验 ----------------

    private void validateCode(Module body, Long appId) {
        if (body.getCode() == null || body.getCode().isBlank()) {
            throw BizException.badRequest("code 不能为空");
        }
        if (body.getName() == null || body.getName().isBlank()) {
            throw BizException.badRequest("name 不能为空");
        }
        if (existsCode(body.getCode(), appId)) {
            throw BizException.conflict("模块 code 已存在: " + body.getCode());
        }
    }
}
