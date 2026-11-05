package ci.fcloud.manager.template;

import ci.fcloud.manager.app.Application;
import ci.fcloud.manager.app.ApplicationMapper;
import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.common.CryptoService;
import ci.fcloud.manager.common.SecurityUtils;
import ci.fcloud.manager.file.FileService;
import ci.fcloud.manager.file.FileVersion;
import ci.fcloud.manager.file.FileVersionMapper;
import ci.fcloud.manager.file.ModuleFile;
import ci.fcloud.manager.file.ModuleFileMapper;
import ci.fcloud.manager.module.Module;
import ci.fcloud.manager.module.ModuleEnv;
import ci.fcloud.manager.module.ModuleMapper;
import ci.fcloud.manager.module.ModuleSubscription;
import ci.fcloud.manager.module.ModuleSubscriptionMapper;
import ci.fcloud.manager.module.Variable;
import ci.fcloud.manager.module.VariableMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final ci.fcloud.manager.module.ModuleEnvMapper moduleEnvMapper;
    private final ModuleSubscriptionMapper subscriptionMapper;
    private final VariableMapper variableMapper;
    private final ModuleFileMapper moduleFileMapper;
    private final FileVersionMapper fileVersionMapper;
    private final ApplicationMapper applicationMapper;
    private final CryptoService cryptoService;
    private final FileService fileService;
    private final TemplateFileService templateFileService;

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

    /**
     * 模板的已绑定模块清单（跨应用），供模板详情展示「谁绑了我」。
     */
    public List<BindingItem> bindingsOf(Long templateId) {
        Template t = requireById(templateId);
        List<Module> modules = moduleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Module>()
                        .eq(Module::getTemplateId, templateId)
                        .orderByAsc(Module::getApplicationId)
                        .orderByAsc(Module::getCode));
        if (modules.isEmpty()) {
            return List.of();
        }
        Map<Long, Application> apps = appMap(modules);
        Instant templateUpdatedAt = t.getUpdatedAt();
        List<BindingItem> out = new ArrayList<>();
        for (Module m : modules) {
            Application a = m.getApplicationId() == null ? null : apps.get(m.getApplicationId());
            boolean pending = templateUpdatedAt != null
                    && (m.getTemplateSyncedAt() == null
                    || templateUpdatedAt.isAfter(m.getTemplateSyncedAt()));
            out.add(new BindingItem(m.getId(), m.getApplicationId(),
                    a == null ? null : a.getCode(),
                    a == null ? null : a.getName(),
                    m.getCode(), m.getTemplateSyncedAt(), pending));
        }
        return out;
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
        templateFileService.deleteAllForTemplate(id);
        for (Module m : linkedModules(id)) {
            // 模板删除不删除已下发内容：origin 改回 MODULE，模块继续拥有这些变量/文件。
            variableMapper.update(null, new LambdaUpdateWrapper<Variable>()
                    .eq(Variable::getModuleId, m.getId())
                    .eq(Variable::getOrigin, "TEMPLATE")
                    .set(Variable::getOrigin, "MODULE"));
            moduleFileMapper.update(null, new LambdaUpdateWrapper<ModuleFile>()
                    .eq(ModuleFile::getModuleId, m.getId())
                    .eq(ModuleFile::getOrigin, "TEMPLATE")
                    .set(ModuleFile::getOrigin, "MODULE"));
            moduleMapper.update(null, new LambdaUpdateWrapper<Module>()
                    .eq(Module::getId, m.getId())
                    .set(Module::getTemplateId, null)
                    .set(Module::getTemplateSyncedAt, null));
        }
        removeById(id);
    }

    private List<Module> linkedModules(Long templateId) {
        return moduleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Module>()
                        .eq(Module::getTemplateId, templateId));
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
        requireById(templateId);
        requireAdmin();
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
            v.setOrigin("TEMPLATE");
            v.setCreatedBy(createdBy);
            // 明文默认值写 global_value；加密类型默认值不落库
            if (tv.getDefaultValue() != null
                    && "PLAIN".equalsIgnoreCase(tv.getValueType())) {
                v.setGlobalValue(tv.getDefaultValue());
            }
            variableMapper.insert(v);
            order++;
        }
        List<Long> envIds = new ArrayList<>();
        if (environmentIds != null) {
            for (Long envId : new LinkedHashSet<>(environmentIds)) {
                if (envId == null) continue;
                ModuleEnv me = new ModuleEnv();
                me.setModuleId(m.getId());
                me.setEnvironmentId(envId);
                moduleEnvMapper.insert(me);
                envIds.add(envId);
            }
        }
        templateFileService.distribute(m.getId(), templateId, envIds);
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
        return buildDiff(m, m.getTemplateId(), true);
    }

    /**
     * 绑定前预览：不要求模块已关联模板，removed 恒为空。
     */
    public TemplateDiff previewBind(Long moduleId, Long templateId) {
        Module m = moduleMapper.selectById(moduleId);
        if (m == null) {
            throw BizException.notFound("模块不存在: " + moduleId);
        }
        requireBindable(m, templateId);
        requireById(templateId);
        return buildDiff(m, templateId, false);
    }

    private TemplateDiff buildDiff(Module m, Long templateId, boolean includeRemoved) {
        List<TemplateVar> tvars = varsOf(templateId);
        List<Variable> mvars = variableMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                        .eq(Variable::getModuleId, m.getId()));

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
        List<VarConflict> conflicts = new ArrayList<>();
        for (TemplateVar tv : tvars) {
            Variable mv = mmap.get(tv.getVarKey());
            if (mv == null) {
                continue;
            }
            if (!"TEMPLATE".equals(mv.getOrigin())) {
                // 模块自有同名变量优先，不自动接管
                conflicts.add(new VarConflict(tv.getVarKey(), defOf(mv), defOf(tv)));
            } else if (!sameDefinition(tv, mv)) {
                changed.add(new Changed(tv.getVarKey(), defOf(tv), defOf(mv)));
            }
        }

        List<Variable> removed = new ArrayList<>();
        if (includeRemoved) {
            for (Variable mv : mvars) {
                if ("TEMPLATE".equals(mv.getOrigin()) && !tmap.containsKey(mv.getVarKey())) {
                    removed.add(mv);
                }
            }
        }

        List<FileStatus> files = fileStatuses(m.getId(), templateId);
        List<RemovedFile> removedFiles = includeRemoved
                ? templateFileService.removedFiles(m.getId(), templateId).stream()
                        .map(r -> new RemovedFile(r.fileKey(), r.environmentCount()))
                        .toList()
                : List.of();

        List<AppConflict> appConflicts = appConflicts(m, templateId);
        return new TemplateDiff(added, changed, removed, conflicts, files, removedFiles,
                appConflicts, templateId, m.getTemplateSyncedAt(), templateUpdatedAt(templateId));
    }

    /**
     * 文件级状态：按 key 汇总每个环境的情况。
     * status = CONFLICT（有自有同名文件）/ UPDATE（模板副本内容过期）/ ADD（部分环境缺失）。
     */
    private List<FileStatus> fileStatuses(Long moduleId, Long templateId) {
        List<TemplateFile> tfiles = templateFileService.list(templateId);
        if (tfiles.isEmpty()) {
            return List.of();
        }
        List<Long> envIds = moduleEnvMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                                .eq(ModuleEnv::getModuleId, moduleId))
                .stream().map(ModuleEnv::getEnvironmentId).distinct().toList();
        if (envIds.isEmpty()) {
            return List.of();
        }
        List<ModuleFile> mfiles = moduleFileMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleFile>()
                        .eq(ModuleFile::getModuleId, moduleId));
        Map<String, Map<Long, ModuleFile>> byKeyEnv = new HashMap<>();
        for (ModuleFile mf : mfiles) {
            byKeyEnv.computeIfAbsent(mf.getFileKey(), k -> new HashMap<>())
                    .put(mf.getEnvironmentId(), mf);
        }
        Map<Long, FileVersion> currentVersions = currentVersions(mfiles);
        List<FileStatus> out = new ArrayList<>();
        for (TemplateFile tf : tfiles) {
            Map<Long, ModuleFile> rows = byKeyEnv.getOrDefault(tf.getFileKey(), Map.of());
            int missing = 0;
            int outdated = 0;
            int conflict = 0;
            for (Long envId : envIds) {
                ModuleFile row = rows.get(envId);
                if (row == null) {
                    missing++;
                } else if (!"TEMPLATE".equals(row.getOrigin())) {
                    conflict++;
                } else {
                    FileVersion v = currentVersions.get(row.getId());
                    boolean same = v != null && tf.getSha256() != null
                            && tf.getSha256().equals(v.getSha256());
                    if (!same) {
                        outdated++;
                    }
                }
            }
            String status = conflict > 0 ? "CONFLICT"
                    : outdated > 0 ? "UPDATE"
                    : missing > 0 ? "ADD" : "OK";
            if (!"OK".equals(status)) {
                out.add(new FileStatus(tf.getFileKey(), tf.getRemark(), status,
                        missing, outdated, conflict));
            }
        }
        return out;
    }

    private Map<Long, FileVersion> currentVersions(List<ModuleFile> files) {
        if (files.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = files.stream().map(ModuleFile::getId).toList();
        Map<Long, FileVersion> out = new HashMap<>();
        for (FileVersion v : fileVersionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileVersion>()
                        .in(FileVersion::getFileId, ids))) {
            for (ModuleFile f : files) {
                if (f.getId().equals(v.getFileId())
                        && Objects.equals(f.getCurrentVersion(), v.getVersion())) {
                    out.put(f.getId(), v);
                }
            }
        }
        return out;
    }

    private Def defOf(TemplateVar tv) {
        return new Def(tv.getValueType(), tv.getRemark(), tv.getIsRequired());
    }

    private Def defOf(Variable v) {
        return new Def(v.getValueType(), v.getRemark(), v.getIsRequired());
    }

    private boolean sameDefinition(TemplateVar tv, Variable mv) {
        return Objects.equals(tv.getValueType() == null ? "PLAIN" : tv.getValueType(),
                mv.getValueType() == null ? "PLAIN" : mv.getValueType())
                && Objects.equals(tv.getRemark(), mv.getRemark())
                && Objects.equals(Boolean.TRUE.equals(tv.getIsRequired()),
                        Boolean.TRUE.equals(mv.getIsRequired()));
    }

    /**
     * 同应用内容冲突检测（只提示，不阻断）：
     * 模板下发的 key 若也被同应用的其他私有模块 / 已订阅共享模块定义，
     * 流水线同时请求这些模块时会出现重复 dotenv key 或同名文件。
     */
    private List<AppConflict> appConflicts(Module m, Long templateId) {
        if (m.getApplicationId() == null) {
            return List.of();
        }
        Long appId = m.getApplicationId();
        Set<String> varKeys = varsOf(templateId).stream()
                .map(TemplateVar::getVarKey).collect(Collectors.toSet());
        Set<String> fileKeys = templateFileService.list(templateId).stream()
                .map(TemplateFile::getFileKey).collect(Collectors.toSet());
        if (varKeys.isEmpty() && fileKeys.isEmpty()) {
            return List.of();
        }

        List<Module> others = new ArrayList<>(moduleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Module>()
                        .eq(Module::getApplicationId, appId)
                        .ne(Module::getId, m.getId())));
        List<Long> sharedIds = subscriptionMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleSubscription>()
                                .eq(ModuleSubscription::getApplicationId, appId))
                .stream().map(ModuleSubscription::getModuleId).distinct().toList();
        if (!sharedIds.isEmpty()) {
            others.addAll(moduleMapper.selectBatchIds(sharedIds));
        }
        if (others.isEmpty()) {
            return List.of();
        }

        Map<Long, Module> otherById = new HashMap<>();
        for (Module other : others) {
            otherById.put(other.getId(), other);
        }
        List<Long> otherIds = new ArrayList<>(otherById.keySet());
        List<AppConflict> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (!varKeys.isEmpty()) {
            for (Variable v : variableMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                            .in(Variable::getModuleId, otherIds)
                            .in(Variable::getVarKey, varKeys))) {
                Module other = otherById.get(v.getModuleId());
                if (other == null) {
                    continue;
                }
                String key = "VAR:" + v.getVarKey() + ":" + other.getId();
                if (seen.add(key)) {
                    out.add(new AppConflict("VAR", v.getVarKey(), other.getCode(),
                            Boolean.TRUE.equals(other.getIsShared())));
                }
            }
        }
        if (!fileKeys.isEmpty()) {
            Set<Long> currentEnvIds = moduleEnvMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                            .eq(ModuleEnv::getModuleId, m.getId()))
                    .stream().map(ModuleEnv::getEnvironmentId).collect(Collectors.toSet());
            for (ModuleFile f : moduleFileMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleFile>()
                            .in(ModuleFile::getModuleId, otherIds)
                            .in(ModuleFile::getFileKey, fileKeys))) {
                Module other = otherById.get(f.getModuleId());
                if (!currentEnvIds.contains(f.getEnvironmentId())) {
                    continue;
                }
                if (other == null) {
                    continue;
                }
                String key = "FILE:" + f.getFileKey() + ":" + other.getId();
                if (seen.add(key)) {
                    out.add(new AppConflict("FILE", f.getFileKey(), other.getCode(),
                            Boolean.TRUE.equals(other.getIsShared())));
                }
            }
        }
        return out;
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
                           List<String> removedKeys, String removedAction,
                           List<String> removedFileKeys) {
        requireAdmin();
        Module m = moduleMapper.selectById(moduleId);
        if (m == null) {
            throw BizException.notFound("模块不存在: " + moduleId);
        }
        if (m.getTemplateId() == null) {
            throw BizException.badRequest("该模块未关联模板");
        }
        if (Boolean.TRUE.equals(m.getIsShared())) {
            throw BizException.badRequest("共享模块不能同步模板");
        }
        TemplateDiff d = buildDiff(m, m.getTemplateId(), true);
        List<String> addedKeys = acceptAdded == null ? List.of() : acceptAdded;
        List<String> changedKeys = acceptChanged == null ? List.of() : acceptChanged;
        List<String> remKeys = removedKeys == null ? List.of() : removedKeys;
        List<String> remFileKeys = removedFileKeys == null ? List.of() : removedFileKeys;

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
            insertTemplateVariable(moduleId, tv, ++maxOrder);
            addedCount++;
        }

        // 2) 定义变更：只改 类型/备注/必填，不动 global_value 与任何覆盖
        for (Changed c : d.changed()) {
            if (!changedKeys.contains(c.varKey())) {
                continue;
            }
            Variable v = variableMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                            .eq(Variable::getModuleId, moduleId)
                            .eq(Variable::getVarKey, c.varKey()));
            if (v == null || !"TEMPLATE".equals(v.getOrigin())) {
                continue;
            }
            TemplateVar tv = new TemplateVar();
            tv.setVarKey(c.varKey());
            tv.setValueType(c.template().valueType());
            tv.setRemark(c.template().remark());
            tv.setIsRequired(c.template().isRequired());
            if (applyTemplateDefinition(v, tv)) {
                changedCount++;
            }
        }

        // 3) 模板已删除的变量：DELETE 删除；KEEP 改为模块自有（下次不再出现）
        boolean delete = "DELETE".equalsIgnoreCase(removedAction);
        for (Variable mv : d.removed()) {
            if (!remKeys.contains(mv.getVarKey())) {
                continue;
            }
            if (delete) {
                variableMapper.deleteById(mv.getId());
                deletedCount++;
            } else {
                mv.setOrigin("MODULE");
                variableMapper.updateById(mv);
            }
        }

        // 4) 文件：模板文件自动对齐到模块已绑环境；同名自有文件跳过
        List<Long> envIds = moduleEnvMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                                .eq(ModuleEnv::getModuleId, moduleId))
                .stream().map(ModuleEnv::getEnvironmentId).toList();
        TemplateFileService.DistributeResult fr =
                templateFileService.distribute(moduleId, m.getTemplateId(), envIds);

        int filesDeleted = 0;
        if (!remFileKeys.isEmpty()) {
            List<ModuleFile> removed = moduleFileMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleFile>()
                            .eq(ModuleFile::getModuleId, moduleId)
                            .eq(ModuleFile::getOrigin, "TEMPLATE")
                            .in(ModuleFile::getFileKey, remFileKeys));
            Set<String> liveTemplateKeys = templateFileService.list(m.getTemplateId())
                    .stream().map(TemplateFile::getFileKey).collect(Collectors.toSet());
            for (ModuleFile file : removed) {
                if (liveTemplateKeys.contains(file.getFileKey())) {
                    continue;
                }
                if (delete) {
                    fileService.deleteInternal(file);
                    filesDeleted++;
                } else {
                    file.setOrigin("MODULE");
                    moduleFileMapper.updateById(file);
                }
            }
        }

        m.setTemplateSyncedAt(Instant.now());
        moduleMapper.updateById(m);
        return new SyncResult(addedCount, changedCount, deletedCount,
                fr.added(), fr.updated(), filesDeleted, fr.conflicts());
    }

    // ---------------- 绑定 / 解绑 ----------------

    /**
     * 把已有私有模块绑定到模板。
     *
     * <p>只接管「模块里不存在的 key」；模块已有的同名变量/文件保留为自有内容，
     * 作为 conflict 返回，不会被覆盖。重复绑定同一模板是幂等的。</p>
     */
    @Transactional
    public BindResult bind(Long moduleId, Long templateId) {
        requireAdmin();
        Module m = moduleMapper.selectById(moduleId);
        if (m == null) {
            throw BizException.notFound("模块不存在: " + moduleId);
        }
        requireBindable(m, templateId);
        requireById(templateId);
        return bindInternal(m, templateId);
    }

    private BindResult bindInternal(Module m, Long templateId) {
        List<TemplateVar> tvars = varsOf(templateId);
        List<Variable> mvars = variableMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                        .eq(Variable::getModuleId, m.getId()));
        Map<String, Variable> existing = mvars.stream()
                .collect(Collectors.toMap(Variable::getVarKey, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        int added = 0;
        int changed = 0;
        int conflicts = 0;
        int maxOrder = mvars.stream()
                .mapToInt(v -> v.getSortOrder() == null ? 0 : v.getSortOrder())
                .max().orElse(-1);
        for (TemplateVar tv : tvars) {
            Variable mv = existing.get(tv.getVarKey());
            if (mv == null) {
                insertTemplateVariable(m.getId(), tv, ++maxOrder);
                added++;
            } else if ("TEMPLATE".equals(mv.getOrigin())) {
                if (applyTemplateDefinition(mv, tv)) {
                    changed++;
                }
            } else {
                conflicts++;
            }
        }

        m.setTemplateId(templateId);
        m.setTemplateSyncedAt(Instant.now());
        moduleMapper.updateById(m);

        List<Long> envIds = moduleEnvMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                                .eq(ModuleEnv::getModuleId, m.getId()))
                .stream().map(ModuleEnv::getEnvironmentId).toList();
        TemplateFileService.DistributeResult fr =
                templateFileService.distribute(m.getId(), templateId, envIds);
        return new BindResult(added, changed, conflicts, fr.added(), fr.updated(),
                fr.conflicts(), envIds.size());
    }

    private void requireBindable(Module m, Long templateId) {
        if (Boolean.TRUE.equals(m.getIsShared()) || m.getApplicationId() == null) {
            throw BizException.badRequest("共享模块不能绑定模板，模板只用于私有模块");
        }
        if (m.getTemplateId() != null && !m.getTemplateId().equals(templateId)) {
            throw BizException.conflict("模块已绑定其他模板，请先解绑");
        }
    }

    private void insertTemplateVariable(Long moduleId, TemplateVar tv, int order) {
        Variable v = new Variable();
        v.setModuleId(moduleId);
        v.setVarKey(tv.getVarKey());
        v.setValueType(tv.getValueType() == null ? "PLAIN" : tv.getValueType());
        v.setRemark(tv.getRemark());
        v.setIsRequired(tv.getIsRequired());
        v.setSortOrder(order);
        v.setOrigin("TEMPLATE");
        v.setCreatedBy(SecurityUtils.currentUserId());
        if (tv.getDefaultValue() != null
                && "PLAIN".equalsIgnoreCase(tv.getValueType())) {
            v.setGlobalValue(tv.getDefaultValue());
        }
        variableMapper.insert(v);
    }

    /**
     * 把模板定义应用到模块变量：只改类型/备注/必填，不动值和覆盖。
     *
     * @return 是否发生了定义变更
     */
    private boolean applyTemplateDefinition(Variable v, TemplateVar tv) {
        String newType = tv.getValueType() == null ? "PLAIN" : tv.getValueType();
        boolean typeChanged = !Objects.equals(newType, v.getValueType());
        boolean remarkChanged = !Objects.equals(tv.getRemark(), v.getRemark());
        boolean requiredChanged = !Objects.equals(Boolean.TRUE.equals(tv.getIsRequired()),
                Boolean.TRUE.equals(v.getIsRequired()));
        if (!typeChanged && !remarkChanged && !requiredChanged) {
            return false;
        }
        if (typeChanged && v.getGlobalValue() != null) {
            String raw = "ENCRYPTED".equals(v.getValueType())
                    ? cryptoService.decrypt(v.getGlobalValue())
                    : v.getGlobalValue();
            v.setGlobalValue("ENCRYPTED".equals(newType)
                    ? cryptoService.encrypt(raw) : raw);
        }
        v.setValueType(newType);
        v.setRemark(tv.getRemark());
        v.setIsRequired(tv.getIsRequired());
        v.setOrigin("TEMPLATE");
        variableMapper.updateById(v);
        return true;
    }

    /**
     * 解绑：默认保留已下发内容，只把 origin 改回 MODULE；可显式删除模板来源项。
     */
    @Transactional
    public UnbindResult unbind(Long moduleId, boolean deleteContent) {
        requireAdmin();
        Module m = moduleMapper.selectById(moduleId);
        if (m == null) {
            throw BizException.notFound("模块不存在: " + moduleId);
        }
        if (Boolean.TRUE.equals(m.getIsShared())) {
            throw BizException.badRequest("共享模块不会绑定模板");
        }
        if (m.getTemplateId() == null) {
            throw BizException.badRequest("该模块未绑定模板");
        }
        long varCount = variableMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                        .eq(Variable::getModuleId, moduleId)
                        .eq(Variable::getOrigin, "TEMPLATE"));
        List<ModuleFile> templateFiles = moduleFileMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleFile>()
                        .eq(ModuleFile::getModuleId, moduleId)
                        .eq(ModuleFile::getOrigin, "TEMPLATE"));

        if (deleteContent) {
            variableMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                    .eq(Variable::getModuleId, moduleId)
                    .eq(Variable::getOrigin, "TEMPLATE"));
            for (ModuleFile file : templateFiles) {
                fileService.deleteInternal(file);
            }
        } else {
            variableMapper.update(null, new LambdaUpdateWrapper<Variable>()
                    .eq(Variable::getModuleId, moduleId)
                    .eq(Variable::getOrigin, "TEMPLATE")
                    .set(Variable::getOrigin, "MODULE"));
            moduleFileMapper.update(null, new LambdaUpdateWrapper<ModuleFile>()
                    .eq(ModuleFile::getModuleId, moduleId)
                    .eq(ModuleFile::getOrigin, "TEMPLATE")
                    .set(ModuleFile::getOrigin, "MODULE"));
        }

        moduleMapper.update(null, new LambdaUpdateWrapper<Module>()
                    .eq(Module::getId, m.getId())
                    .set(Module::getTemplateId, null)
                    .set(Module::getTemplateSyncedAt, null));
        return new UnbindResult((int) varCount, templateFiles.size(), deleteContent);
    }

    /**
     * 模块环境绑定变化后补发模板文件（幂等；内容一致不会产生新版本）。
     */
    @Transactional
    public void materializeFilesIfBound(Long moduleId) {
        Module m = moduleMapper.selectById(moduleId);
        if (m == null || m.getTemplateId() == null || Boolean.TRUE.equals(m.getIsShared())) {
            return;
        }
        List<Long> envIds = moduleEnvMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                                .eq(ModuleEnv::getModuleId, moduleId))
                .stream().map(ModuleEnv::getEnvironmentId).toList();
        templateFileService.distribute(moduleId, m.getTemplateId(), envIds);
    }

    // ---------------- 批量绑定（按模块 code 跨应用） ----------------

    /**
     * 预览：匹配所有私有模块 code，返回每个模块将新增/冲突的数量，不落库。
     */
    public List<BatchBindItem> previewBindBatch(Long templateId, String moduleCode,
                                                List<Long> applicationIds) {
        requireById(templateId);
        List<Module> modules = matchModules(moduleCode, applicationIds);
        Map<Long, Application> apps = appMap(modules);
        Map<Long, Integer> envCounts = envCounts(modules);
        List<BatchBindItem> out = new ArrayList<>();
        for (Module m : modules) {
            int envCount = envCounts.getOrDefault(m.getId(), 0);
            if (m.getTemplateId() != null) {
                out.add(item(m, apps, m.getTemplateId().equals(templateId)
                        ? "ALREADY" : "OTHER_TEMPLATE", 0, 0, 0, 0, 0, 0, 0, envCount));
                continue;
            }
            TemplateDiff d = buildDiff(m, templateId, false);
            int[] fileCounts = countFiles(d.files());
            out.add(item(m, apps, "READY", d.added().size(), 0, d.conflicts().size(),
                    fileCounts[0], fileCounts[1], fileCounts[2],
                    d.appConflicts().size(), envCount));
        }
        return out;
    }

    /**
     * 批量绑定：只处理未绑定模板的私有模块；已绑其他模板的跳过。
     */
    @Transactional
    public List<BatchBindItem> bindBatch(Long templateId, String moduleCode,
                                         List<Long> applicationIds) {
        requireAdmin();
        requireById(templateId);
        List<Module> modules = matchModules(moduleCode, applicationIds);
        Map<Long, Application> apps = appMap(modules);
        Map<Long, Integer> envCounts = envCounts(modules);
        List<BatchBindItem> out = new ArrayList<>();
        for (Module m : modules) {
            int envCount = envCounts.getOrDefault(m.getId(), 0);
            if (m.getTemplateId() != null) {
                out.add(item(m, apps, m.getTemplateId().equals(templateId)
                        ? "ALREADY" : "OTHER_TEMPLATE", 0, 0, 0, 0, 0, 0, 0, envCount));
                continue;
            }
            int appConflicts = appConflicts(m, templateId).size();
            BindResult r = bindInternal(m, templateId);
            out.add(item(m, apps, "BOUND", r.addedVars(), r.changedVars(),
                    r.varConflicts(), r.addedFiles(), r.updatedFiles(), r.fileConflicts(),
                    appConflicts, envCount));
        }
        return out;
    }

    private List<Module> matchModules(String moduleCode, List<Long> applicationIds) {
        if (moduleCode == null || moduleCode.isBlank()) {
            throw BizException.badRequest("moduleCode 不能为空");
        }
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Module> w =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Module>()
                        .eq(Module::getIsShared, false)
                        .eq(Module::getCode, moduleCode);
        if (applicationIds != null && !applicationIds.isEmpty()) {
            w.in(Module::getApplicationId, new LinkedHashSet<>(applicationIds));
        }
        return moduleMapper.selectList(w.orderByAsc(Module::getApplicationId));
    }

    private Map<Long, Application> appMap(List<Module> modules) {
        Set<Long> ids = modules.stream().map(Module::getApplicationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Application> out = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Application a : applicationMapper.selectBatchIds(ids)) {
                out.put(a.getId(), a);
            }
        }
        return out;
    }

    private BatchBindItem item(Module m, Map<Long, Application> apps, String status,
                               int addedVars, int changedVars, int varConflicts,
                               int addedFiles, int updatedFiles, int fileConflicts,
                               int appConflicts, int envCount) {
        Application a = m.getApplicationId() == null ? null : apps.get(m.getApplicationId());
        return new BatchBindItem(m.getId(), m.getApplicationId(),
                a == null ? null : a.getCode(), m.getCode(), status,
                addedVars, changedVars, varConflicts, addedFiles, updatedFiles, fileConflicts,
                appConflicts, envCount);
    }

    /** 模块已绑定的环境数量：绑定模板不改环境，0 环境的模块收不到模板文件 */
    private Map<Long, Integer> envCounts(List<Module> modules) {
        if (modules.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = modules.stream().map(Module::getId).toList();
        Map<Long, Integer> out = new HashMap<>();
        for (ModuleEnv me : moduleEnvMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleEnv>()
                        .in(ModuleEnv::getModuleId, ids))) {
            out.merge(me.getModuleId(), 1, Integer::sum);
        }
        return out;
    }

    private int[] countFiles(List<FileStatus> files) {
        int add = 0;
        int update = 0;
        int conflict = 0;
        for (FileStatus f : files) {
            if (f.conflictEnvironments() > 0) conflict++;
            else if (f.outdatedEnvironments() > 0) update++;
            else if (f.missingEnvironments() > 0) add++;
        }
        return new int[]{add, update, conflict};
    }

    private static void requireAdmin() {
        if (!SecurityUtils.isSystemAdmin()) {
            throw new BizException(40300, "只有系统管理员可以绑定/解绑/同步模板");
        }
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

    public record VarConflict(String varKey, Def module, Def template) {
    }

    /** 文件级状态：counts 按环境计数；status = ADD / UPDATE / CONFLICT。 */
    public record FileStatus(String fileKey, String remark, String status,
                             int missingEnvironments, int outdatedEnvironments,
                             int conflictEnvironments) {
    }

    public record RemovedFile(String fileKey, int environmentCount) {
    }

    public record AppConflict(String kind, String key, String moduleCode, boolean shared) {
    }

    public record TemplateDiff(List<TemplateVar> added, List<Changed> changed,
                               List<Variable> removed, List<VarConflict> conflicts,
                               List<FileStatus> files, List<RemovedFile> removedFiles,
                               List<AppConflict> appConflicts,
                               Long templateId, Instant moduleSyncedAt,
                               Instant templateUpdatedAt) {

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

    public record SyncResult(int added, int changed, int deleted,
                             int filesAdded, int filesUpdated, int filesDeleted,
                             int fileConflicts) {
    }

    public record BindResult(int addedVars, int changedVars, int varConflicts,
                             int addedFiles, int updatedFiles, int fileConflicts,
                             int environments) {
    }

    public record UnbindResult(int variables, int files, boolean deleted) {
    }

    public record BatchBindItem(Long moduleId, Long applicationId, String applicationCode,
                                String moduleCode, String status,
                                int addedVars, int changedVars, int varConflicts,
                                int addedFiles, int updatedFiles, int fileConflicts,
                                int appConflicts, int envCount) {
    }

    /**
     * 模板绑定关系的一条记录：哪个应用的哪个模块绑了此模板、上次同步时间、是否待同步。
     */
    public record BindingItem(Long moduleId, Long applicationId, String applicationCode,
                              String applicationName, String moduleCode,
                              Instant syncedAt, boolean pending) {
    }
}
