package ci.fcloud.manager.module;

import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.common.CryptoService;
import ci.fcloud.manager.env.Environment;
import ci.fcloud.manager.env.EnvironmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * §2.2 变量解析优先级（核心算法，必须严格实现）。
 *
 * <pre>
 * 对「共享模块 + 应用 A + 环境 E + 变量 V」，按以下顺序取第一个命中的值：
 * 1. override(V, E, A)          应用级覆盖 · 当前环境
 * 2. override(V, E, null)       模块级覆盖 · 当前环境
 * 3. 沿 E 的父链逐级重复 1、2    应用级与模块级覆盖，按父→祖父…逐环境依次尝试
 * 4. V.global_value             全局值
 * 5. 缺失                       该 key 不输出
 * </pre>
 *
 * 私有模块跳过第 1 步（无 application_id）。
 * 父链遍历需防环（visited set）。
 * 明文变量直接返回值；加密变量返回解密后的明文（仅流水线 API）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VariableResolver {

    /** 无权限查看明文时的占位符（§6.4） */
    public static final String MASKED_PLACEHOLDER = "••••••";
    /** console dotenv 导出时无权限用户的占位符（§6.4） */
    public static final String EXPORT_MASKED = "__MASKED__";

    private final VariableMapper variableMapper;
    private final VariableOverrideMapper overrideMapper;
    private final EnvironmentMapper environmentMapper;
    private final CryptoService cryptoService;

    /**
     * 解析单个变量。
     */
    public ResolvedValue resolveVariable(Variable variable, Long applicationId,
                                         Long environmentId, boolean revealPlain) {
        if (variable == null) {
            return ResolvedValue.notFound();
        }
        boolean encrypted = "ENCRYPTED".equalsIgnoreCase(variable.getValueType());

        // 收集该变量在该应用下的全部覆盖（按环境索引）
        OverrideIndex idx = overridesByEnvironment(variable.getId(), applicationId);

        // 环境链（含自身，沿父链向上），防环
        Deque<Long> chain = envChain(environmentId);

        // 第 1、2、3 步：按环境链顺序，每个环境先试应用级、再试模块级
        boolean firstEnv = true;
        for (Long envId : chain) {
            // 私有模块跳过第 1 步（无 application_id）
            if (applicationId != null) {
                VariableOverride ao = idx.appLevel().get(envId);
                if (ao != null) {
                    return emit(ao.getValue(), encrypted, revealPlain,
                            firstEnv ? ResolvedValue.Source.APP_OVERRIDE
                                    : ResolvedValue.Source.APP_OVERRIDE_ANCESTOR,
                            envId);
                }
            }
            VariableOverride mo = idx.moduleLevel().get(envId);
            if (mo != null) {
                return emit(mo.getValue(), encrypted, revealPlain,
                        firstEnv ? ResolvedValue.Source.MODULE_OVERRIDE
                                : ResolvedValue.Source.MODULE_OVERRIDE_ANCESTOR,
                        envId);
            }
            firstEnv = false;
        }

        // 第 4 步：全局值
        if (variable.getGlobalValue() != null) {
            return emit(variable.getGlobalValue(), encrypted, revealPlain,
                    ResolvedValue.Source.GLOBAL, null);
        }

        // 第 5 步：缺失 → 该 key 不输出
        return ResolvedValue.notFound();
    }

    /**
     * §10.5 继承链解释：返回「当前环境 → 父环境… → 全局值」每一层的定义情况。
     *
     * <p>只用于 UI 展示值的来源。解析优先级仍以 {@link #resolveVariable} 为准，
     * 这里逐层读取 override，不做任何"猜测"。</p>
     */
    public List<ChainLevel> explain(Variable variable, Long applicationId,
                                    Long environmentId, boolean revealPlain) {
        List<ChainLevel> out = new ArrayList<>();
        if (variable == null) {
            return out;
        }
        boolean encrypted = "ENCRYPTED".equalsIgnoreCase(variable.getValueType());

        OverrideIndex idx = overridesByEnvironment(variable.getId(), applicationId);

        for (Long envId : envChain(environmentId)) {
            ChainLevel level = new ChainLevel();
            level.setEnvironmentId(envId);
            VariableOverride hit = applicationId == null ? null : idx.appLevel().get(envId);
            String source = hit == null ? null : ResolvedValue.Source.APP_OVERRIDE.name();
            if (hit == null) {
                hit = idx.moduleLevel().get(envId);
                source = hit == null ? null : ResolvedValue.Source.MODULE_OVERRIDE.name();
            }
            if (hit != null) {
                level.setDefined(true);
                level.setSource(source);
                level.setMasked(encrypted && !revealPlain);
                level.setValue(encrypted
                        ? (revealPlain ? cryptoService.decrypt(hit.getValue())
                        : MASKED_PLACEHOLDER)
                        : hit.getValue());
            }
            out.add(level);
        }

        ChainLevel global = new ChainLevel();
        global.setEnvironmentId(null);
        if (variable.getGlobalValue() != null) {
            global.setDefined(true);
            global.setSource(ResolvedValue.Source.GLOBAL.name());
            global.setMasked(encrypted && !revealPlain);
            global.setValue(encrypted
                    ? (revealPlain ? cryptoService.decrypt(variable.getGlobalValue())
                    : MASKED_PLACEHOLDER)
                    : variable.getGlobalValue());
        }
        out.add(global);
        return out;
    }

    /**
     * 批量解析某模块在某环境下的全部变量，按 sort_order 排序。
     */
    public List<ResolvedEntry> resolveAll(Long moduleId, Long applicationId,
                                          Long environmentId, boolean revealPlain) {
        List<Variable> vars = variableMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                        .eq(Variable::getModuleId, moduleId)
                        .orderByAsc(Variable::getSortOrder)
                        .orderByAsc(Variable::getId));
        return vars.stream()
                .map(v -> new ResolvedEntry(v, resolveVariable(v, applicationId,
                        environmentId, revealPlain)))
                .toList();
    }

    private List<VariableOverride> overridesOf(Long variableId, Long applicationId) {
        if (variableId == null) {
            return List.of();
        }
        var w = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VariableOverride>()
                .eq(VariableOverride::getVariableId, variableId);
        // 应用级覆盖只看该应用的；模块级覆盖（application_id 为空）对所有应用生效
        if (applicationId != null) {
            w.and(x -> x.isNull(VariableOverride::getApplicationId)
                    .or().eq(VariableOverride::getApplicationId, applicationId));
        } else {
            w.isNull(VariableOverride::getApplicationId);
        }
        List<VariableOverride> raw = overrideMapper.selectList(w);

        // 防御性二次过滤：即使查询条件被改动，也不得把别的应用的覆盖泄漏过来
        List<VariableOverride> filtered = raw.stream()
                .filter(o -> o.getApplicationId() == null
                        || o.getApplicationId().equals(applicationId))
                .toList();
        return filtered;
    }

    /**
     * 按「应用级 / 模块级」把覆盖分成两张表，键为环境 id。
     * applicationId 为空时只会查到模块级覆盖（见 {@link #overridesOf}）。
     */
    private OverrideIndex overridesByEnvironment(Long variableId, Long applicationId) {
        Map<Long, VariableOverride> appLevel = new HashMap<>();
        Map<Long, VariableOverride> moduleLevel = new HashMap<>();
        for (VariableOverride o : overridesOf(variableId, applicationId)) {
            if (o.getApplicationId() == null) {
                moduleLevel.put(o.getEnvironmentId(), o);
            } else {
                appLevel.put(o.getEnvironmentId(), o);
            }
        }
        return new OverrideIndex(appLevel, moduleLevel);
    }

    private record OverrideIndex(Map<Long, VariableOverride> appLevel,
                                 Map<Long, VariableOverride> moduleLevel) {
    }

    private Deque<Long> envChain(Long environmentId) {
        Deque<Long> out = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        Long cur = environmentId;
        while (cur != null && visited.add(cur)) {
            out.add(cur);
            cur = parentEnvId(cur);
        }
        if (visited.size() > 64) {
            throw new BizException(40000, "环境继承链异常（可能存在环）");
        }
        return out;
    }

    private Long parentEnvId(Long envId) {
        Environment env = environmentMapper.selectById(envId);
        return env == null ? null : env.getParentEnvId();
    }

    private ResolvedValue emit(String stored, boolean encrypted, boolean revealPlain,
                               ResolvedValue.Source source, Long sourceEnvId) {
        if (encrypted) {
            if (!revealPlain) {
                return ResolvedValue.builder()
                        .value(MASKED_PLACEHOLDER)
                        .source(source)
                        .sourceEnvironmentId(sourceEnvId)
                        .masked(true)
                        .found(true)
                        .build();
            }
            return ResolvedValue.builder()
                    .value(cryptoService.decrypt(stored))
                    .source(source)
                    .sourceEnvironmentId(sourceEnvId)
                    .masked(false)
                    .found(true)
                    .build();
        }
        return ResolvedValue.builder()
                .value(stored)
                .source(source)
                .sourceEnvironmentId(sourceEnvId)
                .masked(false)
                .found(true)
                .build();
    }

    /**
     * 变量 + 解析结果。
     */
    public record ResolvedEntry(Variable variable, ResolvedValue resolved) {
    }

    /**
     * 继承链上的一层（environmentId 为 null 表示全局值）。
     */
    @lombok.Data
    public static class ChainLevel {
        private Long environmentId;
        private boolean defined;
        private boolean masked;
        private String value;
        private String source;
    }
}
