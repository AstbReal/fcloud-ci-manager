package ci.fcloud.hub.template;

import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.common.CryptoService;
import ci.fcloud.hub.common.SecurityUtils;
import ci.fcloud.hub.module.Module;
import ci.fcloud.hub.module.ModuleMapper;
import ci.fcloud.hub.module.Variable;
import ci.fcloud.hub.module.VariableMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * §2.5 / §5.8 模板库：CRUD、实例化、diff、选择性同步、另存为模板。
 *
 * 关键规则（§2.5）：
 * - 模板 = 变量定义集合，仅系统管理员可维护
 * - 模块通过 template_id 保持关联；template.updated_at > module.template_synced_at 时
 *   UI 显示"有更新"徽标
 * - 实例化：明文默认值写入 global_value，加密类型的默认值不落库
 * - diff 式选择性同步：added / changed / removed
 * - 同步永远不覆盖已填的值
 */
@Service
@RequiredArgsConstructor
public class TemplateService extends ServiceImpl<TemplateMapper, Template> {

    private final TemplateVarMapper templateVarMapper;
    private final ModuleMapper moduleMapper;
    private final ci.fcloud.hub.module.ModuleEnvMapper moduleEnvMapper;
    private final VariableMapper variableMapper;
    private final CryptoService cryptoService;

    // ---------------- CRUD ----------------

    public List<Template> listAll() {
        return lambdaQuery().orderByDesc(Template::getUpdatedAt).list();
    }

    /**
     * 模板分组重命名 / 清空：按 group_name 批量改，to 为空表示移到「未分组」。
     */
    @Transactional
    public int renameGroup(String from, String to) {
        if (from == null || from.isBlank()) {
            throw BizException.badRequest("分组名不能为空");
        }
        // 分组只是归类信息，不动 updated_at，避免误触发「模板待同步」徽标
        return lambdaUpdate()
                .eq(Template::getGroupName, from)
                .set(Template::getGroupName, to == null || to.isBlank() ? null : to)
                .update() ? 1 : 0;
    }

    public Template requireById(Long id) {
        Template t = getById(id);
        if (t == null) {
            throw BizException.notFound("模板不存在: " + id);
        }
        return t;
    }

    public List<TemplateVar> varsOf(Long templateId) {
        return templateVarMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TemplateVar>()
                        .eq(TemplateVar::getTemplateId, templateId)
                        .orderByAsc(TemplateVar::getSortOrder)
                        .orderByAsc(TemplateVar::getId));
    }

    /**
     * §2.5 徽标口径：template.updated_at > module.template_synced_at 即待同步。
     * 模板列表用它显示「n 模块待同步」。
     */
    public long pendingModuleCount(Long templateId) {
        Template t = getById(templateId);
        if (t == null || t.getUpdatedAt() == null) {
            return 0;
        }
        return moduleMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Module>()
                        .eq(Module::getTemplateId, templateId)
                        .and(w -> w.isNull(Module::getTemplateSyncedAt)
                                .or().lt(Module::getTemplateSyncedAt, t.getUpdatedAt())));
    }

    @Transactional
    public Template create(Template body, List<TemplateVar> vars) {
        validate(body);
        if (lambdaQuery().eq(Template::getCode, body.getCode()).count() > 0) {
            throw BizException.conflict("模板 code 已存在: " + body.getCode());
        }
        Template t = new Template();
        t.setCode(body.getCode());
        t.setName(body.getName());
        t.setDescription(body.getDescription());
        t.setGroupName(body.getGroupName());
        t.setCreatedBy(SecurityUtils.currentUserId());
        save(t);
        replaceVars(t.getId(), vars);
        touch(t.getId());
        return getById(t.getId());
    }

    /**
     * §5.8 改（带 vars 全量保存）。
     */
    @Transactional
    public Template update(Long id, Template body, List<TemplateVar> vars) {
        Template t = requireById(id);
        if (body.getCode() != null && !body.getCode().equals(t.getCode())) {
            long dup = lambdaQuery().eq(Template::getCode, body.getCode())
                    .ne(Template::getId, id).count();
            if (dup > 0) {
                throw BizException.conflict("模板 code 已存在: " + body.getCode());
            }
            t.setCode(body.getCode());
        }
        if (body.getName() != null) {
            t.setName(body.getName());
        }
        if (body.getDescription() != null) {
            t.setDescription(body.getDescription());
        }
        if (body.getGroupName() != null) {
            t.setGroupName(body.getGroupName());
        }
        updateById(t);
        if (vars != null) {
            replaceVars(id, vars);
        }
        touch(id);
        return getById(id);
    }

    /**
     * §5.8 删（模块 template_id 置空）。
     */
    @Transactional
    public void delete(Long id) {
        requireById(id);
        // 解除模块关联（软关联，schema 无外键）
        moduleMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Module>()
                        .eq(Module::getTemplateId, id)
                        .set(Module::getTemplateId, null)
                        .set(Module::getTemplateSyncedAt, null));
        removeById(id);
    }

    private void replaceVars(Long templateId, List<TemplateVar> vars) {
        templateVarMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TemplateVar>()
                        .eq(TemplateVar::getTemplateId, templateId));
        if (vars == null) {
            return;
        }
        int order = 0;
        for (TemplateVar v : vars) {
            if (v.getVarKey() == null || v.getVarKey().isBlank()) {
                throw BizException.badRequest("模板变量 key 不能为空");
            }
            TemplateVar nv = new TemplateVar();
            nv.setTemplateId(templateId);
            nv.setVarKey(v.getVarKey());
            nv.setValueType(v.getValueType() == null ? "PLAIN" : v.getValueType());
            nv.setRemark(v.getRemark());
            nv.setDefaultValue(v.getDefaultValue());
            nv.setIsRequired(Boolean.TRUE.equals(v.getIsRequired()));
            nv.setSortOrder(v.getSortOrder() == null ? order : v.getSortOrder());
            order++;
            templateVarMapper.insert(nv);
        }
    }

    /**
     * 更新模板的 updated_at（§2.5 徽标依赖它）。
     */
    public void touch(Long templateId) {
        lambdaUpdate().eq(Template::getId, templateId)
                .set(Template::getUpdatedAt, Instant.now()).update();
    }

    // ---------------- 实例化 ----------------

    /**
     * §2.5 / §5.8 实例化：复制定义到模块变量。
     * 明文默认值写入 global_value；加密类型的默认值不落库。
     */
    @Transactional
    public Module instantiate(Long templateId, Long applicationId, String code, String name,
                              Long createdBy, List<Long> environmentIds, String groupName) {
        Template t = requireById(templateId);
        Module m = new Module();
        m.setApplicationId(applicationId);
        m.setCode(code);
        m.setName(name == null ? code : name);
        m.setGroupName(groupName);
        m.setIsShared(false);
        m.setTemplateId(templateId);
        m.setTemplateSyncedAt(Instant.now());
        m.setCreatedBy(createdBy);
        moduleMapper.insert(m);

        List<TemplateVar> tvars = varsOf(templateId);
        int order = 0;
        for (TemplateVar tv : tvars) {
            Variable v = new Variable();
            v.setModuleId(m.getId());
            v.setVarKey(tv.getVarKey());
            v.setValueType(tv.getValueType());
            v.setRemark(tv.getRemark());
            v.setIsRequired(tv.getIsRequired());
            v.setSortOrder(tv.getSortOrder() == null ? order : tv.getSortOrder());
            v.setCreatedBy(createdBy);
            // 明文默认值写 global_value；加密类型默认值不落库
            if (tv.getDefaultValue() != null
                    && "PLAIN".equalsIgnoreCase(tv.getValueType())) {
                v.setGlobalValue(tv.getDefaultValue());
            }
            variableMapper.insert(v);
            order++;
        }
        if (environmentIds != null) {
            for (Long envId : new java.util.LinkedHashSet<>(environmentIds)) {
                if (envId == null) continue;
                ci.fcloud.hub.module.ModuleEnv me = new ci.fcloud.hub.module.ModuleEnv();
                me.setModuleId(m.getId());
                me.setEnvironmentId(envId);
                moduleEnvMapper.insert(me);
            }
        }
        return m;
    }

    // ---------------- diff ----------------

    /**
     * §5.8 模板 diff：{added, changed, removed}
     *
     * - added：模板有、模块没有的 key
     * - changed：两边都有但 类型/备注/必填 不同
     * - removed：模块有、模板没有的 key
     */
    public TemplateDiff diff(Long moduleId) {
        Module m = moduleMapper.selectById(moduleId);
        if (m == null) {
            throw BizException.notFound("模块不存在: " + moduleId);
        }
        if (m.getTemplateId() == null) {
            throw BizException.badRequest("该模块未关联模板");
        }
        List<TemplateVar> tvars = varsOf(m.getTemplateId());
        List<Variable> mvars = variableMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                        .eq(Variable::getModuleId, moduleId));

        Map<String, TemplateVar> tmap = tvars.stream()
                .collect(Collectors.toMap(TemplateVar::getVarKey, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Map<String, Variable> mmap = mvars.stream()
                .collect(Collectors.toMap(Variable::getVarKey, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        List<TemplateVar> added = new ArrayList<>();
        for (TemplateVar tv : tvars) {
            if (!mmap.containsKey(tv.getVarKey())) {
                added.add(tv);
            }
        }

        List<Changed> changed = new ArrayList<>();
        for (TemplateVar tv : tvars) {
            Variable mv = mmap.get(tv.getVarKey());
            if (mv == null) {
                continue;
            }
            boolean typeChanged = !Objects.equals(
                    tv.getValueType() == null ? "PLAIN" : tv.getValueType(),
                    mv.getValueType() == null ? "PLAIN" : mv.getValueType());
            boolean remarkChanged = !Objects.equals(tv.getRemark(), mv.getRemark());
            boolean requiredChanged = !Objects.equals(
                    Boolean.TRUE.equals(tv.getIsRequired()),
                    Boolean.TRUE.equals(mv.getIsRequired()));
            if (typeChanged || remarkChanged || requiredChanged) {
                changed.add(new Changed(tv.getVarKey(),
                        new Def(tv.getValueType(), tv.getRemark(), tv.getIsRequired()),
                        new Def(mv.getValueType(), mv.getRemark(), mv.getIsRequired())));
            }
        }

        List<Variable> removed = new ArrayList<>();
        for (Variable mv : mvars) {
            if (!tmap.containsKey(mv.getVarKey())) {
                removed.add(mv);
            }
        }

        return new TemplateDiff(added, changed, removed,
                m.getTemplateId(), m.getTemplateSyncedAt(), templateUpdatedAt(m.getTemplateId()));
    }

    private Instant templateUpdatedAt(Long templateId) {
        Template t = getById(templateId);
        return t == null ? null : t.getUpdatedAt();
    }

    // ---------------- 同步 ----------------

    /**
     * §5.8 选择性同步。同步永远不覆盖已填的值（§2.5）。
     *
     * @param acceptAdded   要引入的新增 key
     * @param acceptChanged 要采纳定义变更的 key
     * @param removedKeys   模板已删除的 key
     * @param removedAction DELETE（删除模块变量）或 KEEP（保留为模块自有变量）
     */
    @Transactional
    public SyncResult sync(Long moduleId, List<String> acceptAdded, List<String> acceptChanged,
                           List<String> removedKeys, String removedAction) {
        Module m = moduleMapper.selectById(moduleId);
        if (m == null) {
            throw BizException.notFound("模块不存在: " + moduleId);
        }
        if (m.getTemplateId() == null) {
            throw BizException.badRequest("该模块未关联模板");
        }
        TemplateDiff d = diff(moduleId);
        List<String> addedKeys = acceptAdded == null ? List.of() : acceptAdded;
        List<String> changedKeys = acceptChanged == null ? List.of() : acceptChanged;
        List<String> remKeys = removedKeys == null ? List.of() : removedKeys;

        int addedCount = 0;
        int changedCount = 0;
        int deletedCount = 0;

        // 1) 新增：引入定义（明文默认值落 global_value；加密默认值不落库）
        int maxOrder = variableMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                                .eq(Variable::getModuleId, moduleId))
                .stream().mapToInt(v -> v.getSortOrder() == null ? 0 : v.getSortOrder())
                .max().orElse(-1);
        for (TemplateVar tv : d.added()) {
            if (!addedKeys.contains(tv.getVarKey())) {
                continue;
            }
            Variable v = new Variable();
            v.setModuleId(moduleId);
            v.setVarKey(tv.getVarKey());
            v.setValueType(tv.getValueType() == null ? "PLAIN" : tv.getValueType());
            v.setRemark(tv.getRemark());
            v.setIsRequired(tv.getIsRequired());
            v.setSortOrder(++maxOrder);
            v.setCreatedBy(SecurityUtils.currentUserId());
            if (tv.getDefaultValue() != null
                    && "PLAIN".equalsIgnoreCase(tv.getValueType())) {
                v.setGlobalValue(tv.getDefaultValue());
            }
            variableMapper.insert(v);
            addedCount++;
        }

        // 2) 定义变更：只改 类型/备注/必填，**不动 global_value 与任何覆盖**
        for (Changed c : d.changed()) {
            if (!changedKeys.contains(c.varKey())) {
                continue;
            }
            Variable v = variableMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                            .eq(Variable::getModuleId, moduleId)
                            .eq(Variable::getVarKey, c.varKey()));
            if (v == null) {
                continue;
            }
            String newType = c.template().valueType() == null
                    ? "PLAIN" : c.template().valueType();
            // 类型变更：已有全局值需要重新编码（值本身不变，保证"不覆盖已填的值"）
            if (!Objects.equals(newType, v.getValueType()) && v.getGlobalValue() != null) {
                String raw = "ENCRYPTED".equals(v.getValueType())
                        ? cryptoService.decrypt(v.getGlobalValue())
                        : v.getGlobalValue();
                v.setGlobalValue("ENCRYPTED".equals(newType)
                        ? cryptoService.encrypt(raw) : raw);
            }
            v.setValueType(newType);
            v.setRemark(c.template().remark());
            v.setIsRequired(c.template().isRequired());
            variableMapper.updateById(v);
            changedCount++;
        }

        // 3) 模板已删除的变量
        if ("DELETE".equalsIgnoreCase(removedAction)) {
            for (Variable mv : d.removed()) {
                if (!remKeys.contains(mv.getVarKey())) {
                    continue;
                }
                variableMapper.deleteById(mv.getId());
                deletedCount++;
            }
        }
        // KEEP：什么都不做，保留为模块自有变量

        // 4) 记录同步时间（§2.5 徽标）
        moduleMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Module>()
                        .eq(Module::getId, moduleId)
                        .set(Module::getTemplateSyncedAt, Instant.now()));

        return new SyncResult(addedCount, changedCount, deletedCount);
    }

    // ---------------- 另存为模板 ----------------

    /**
     * §5.8 从模块反哺模板。
     */
    @Transactional
    public Template saveAsTemplate(Long moduleId, String code, String name, String groupName) {
        Module m = moduleMapper.selectById(moduleId);
        if (m == null) {
            throw BizException.notFound("模块不存在: " + moduleId);
        }
        if (lambdaQuery().eq(Template::getCode, code).count() > 0) {
            throw BizException.conflict("模板 code 已存在: " + code);
        }
        Template t = new Template();
        t.setCode(code);
        t.setName(name == null ? code : name);
        t.setDescription("from module " + m.getCode());
        t.setGroupName(groupName);
        t.setCreatedBy(SecurityUtils.currentUserId());
        save(t);

        List<Variable> vars = variableMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                        .eq(Variable::getModuleId, moduleId)
                        .orderByAsc(Variable::getSortOrder));
        int order = 0;
        for (Variable v : vars) {
            TemplateVar tv = new TemplateVar();
            tv.setTemplateId(t.getId());
            tv.setVarKey(v.getVarKey());
            tv.setValueType(v.getValueType());
            tv.setRemark(v.getRemark());
            tv.setIsRequired(v.getIsRequired());
            tv.setSortOrder(order++);
            // 加密变量的全局值不进入模板默认值（避免以明文/密文泄漏）
            if ("PLAIN".equals(v.getValueType())) {
                tv.setDefaultValue(v.getGlobalValue());
            }
            templateVarMapper.insert(tv);
        }
        return getById(t.getId());
    }

    private void validate(Template body) {
        if (body.getCode() == null || body.getCode().isBlank()) {
            throw BizException.badRequest("code 不能为空");
        }
        if (body.getName() == null || body.getName().isBlank()) {
            throw BizException.badRequest("name 不能为空");
        }
    }

    // ---------------- DTO ----------------

    public record Def(String valueType, String remark, Boolean isRequired) {
    }

    public record Changed(String varKey, Def template, Def module) {
    }

    public record TemplateDiff(List<TemplateVar> added, List<Changed> changed,
                               List<Variable> removed, Long templateId,
                               Instant moduleSyncedAt, Instant templateUpdatedAt) {

        /**
         * §2.5 徽标：template.updated_at > module.template_synced_at 表示有更新。
         * 用 getter 形式（getHasUpdate）以便 Jackson 序列化为 JSON 字段。
         */
        public boolean getHasUpdate() {
            if (templateUpdatedAt == null) {
                return false;
            }
            return moduleSyncedAt == null || templateUpdatedAt.isAfter(moduleSyncedAt);
        }
    }

    public record SyncResult(int added, int changed, int deleted) {
    }
}
