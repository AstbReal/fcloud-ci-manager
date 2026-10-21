package ci.fcloud.hub.module;

import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.common.CryptoService;
import ci.fcloud.hub.common.SecurityUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * §5.5 变量矩阵：定义 / 全局值 / 覆盖 / 加密。
 * §6.4 加密变量可见性由 PermissionService 判定。
 */
@Service
@RequiredArgsConstructor
public class VariableService extends ServiceImpl<VariableMapper, Variable> {

    private final VariableOverrideMapper overrideMapper;
    private final CryptoService cryptoService;
    private final PermissionService permissionService;
    private final VariableResolver resolver;

    public Variable requireById(Long id) {
        Variable v = getById(id);
        if (v == null) {
            throw BizException.notFound("变量不存在: " + id);
        }
        return v;
    }

    public List<Variable> listByModule(Long moduleId) {
        return lambdaQuery().eq(Variable::getModuleId, moduleId)
                .orderByAsc(Variable::getSortOrder)
                .orderByAsc(Variable::getId)
                .list();
    }

    /**
     * §5.5 新增定义。
     */
    @Transactional
    public Variable create(Long moduleId, Variable body) {
        permissionService.requireCanEdit(moduleId);
        if (body.getVarKey() == null || body.getVarKey().isBlank()) {
            throw BizException.badRequest("varKey 不能为空");
        }
        long dup = lambdaQuery().eq(Variable::getModuleId, moduleId)
                .eq(Variable::getVarKey, body.getVarKey()).count();
        if (dup > 0) {
            throw BizException.conflict("模块内 key 已存在: " + body.getVarKey());
        }
        String valueType = body.getValueType() == null ? "PLAIN" : body.getValueType();
        if (!"PLAIN".equals(valueType) && !"ENCRYPTED".equals(valueType)) {
            throw BizException.badRequest("valueType 必须是 PLAIN 或 ENCRYPTED");
        }

        Variable v = new Variable();
        v.setModuleId(moduleId);
        v.setVarKey(body.getVarKey());
        v.setValueType(valueType);
        v.setRemark(body.getRemark());
        v.setIsRequired(Boolean.TRUE.equals(body.getIsRequired()));
        v.setSortOrder(body.getSortOrder() == null ? nextSortOrder(moduleId) : body.getSortOrder());
        v.setCreatedBy(SecurityUtils.currentUserId());

        // §2.1 全局值：与"环境覆盖"二选一；加密变量存密文
        String global = body.getGlobalValue();
        if (global != null && !global.isEmpty()) {
            if ("ENCRYPTED".equals(valueType)) {
                // 加密类型：明文入库前加密
                v.setGlobalValue(cryptoService.encrypt(global));
            } else {
                v.setGlobalValue(global);
            }
        }
        save(v);
        return v;
    }

    /**
     * §5.5 改定义（key 不可改，可改类型/备注/必填）。
     */
    public Variable updateDefinition(Long moduleId, Long varId, Variable body) {
        permissionService.requireCanEdit(moduleId);
        Variable v = requireById(varId);
        if (!v.getModuleId().equals(moduleId)) {
            throw BizException.badRequest("变量不属于该模块");
        }
        if (body.getValueType() != null) {
            if (!"PLAIN".equals(body.getValueType()) && !"ENCRYPTED".equals(body.getValueType())) {
                throw BizException.badRequest("valueType 必须是 PLAIN 或 ENCRYPTED");
            }
            // 类型变更时，已有全局值需要重新按新类型编码
            if (!body.getValueType().equals(v.getValueType()) && v.getGlobalValue() != null) {
                String raw = "ENCRYPTED".equals(v.getValueType())
                        ? cryptoService.decrypt(v.getGlobalValue())
                        : v.getGlobalValue();
                v.setGlobalValue("ENCRYPTED".equals(body.getValueType())
                        ? cryptoService.encrypt(raw) : raw);
            }
            v.setValueType(body.getValueType());
        }
        if (body.getRemark() != null) {
            v.setRemark(body.getRemark());
        }
        if (body.getIsRequired() != null) {
            v.setIsRequired(body.getIsRequired());
        }
        updateById(v);
        return v;
    }

    /**
     * §5.5 删定义（连带覆盖，schema ON DELETE CASCADE）。
     */
    @Transactional
    public void delete(Long moduleId, Long varId) {
        permissionService.requireCanEdit(moduleId);
        Variable v = requireById(varId);
        if (!v.getModuleId().equals(moduleId)) {
            throw BizException.badRequest("变量不属于该模块");
        }
        removeById(varId);
    }

    public void sort(Long moduleId, List<Long> ids) {
        permissionService.requireCanEdit(moduleId);
        if (ids == null) {
            return;
        }
        int order = 0;
        for (Long id : ids) {
            lambdaUpdate().eq(Variable::getId, id).eq(Variable::getModuleId, moduleId)
                    .set(Variable::getSortOrder, order++).update();
        }
    }

    // ---------------- 设值：全局 / 环境覆盖 / 应用级覆盖 ----------------

    /**
     * §5.5 PUT /variables/{varId}/value
     * scope = GLOBAL：设全局值；scope = ENV：设覆盖（可带 environmentId/applicationId）。
     */
    @Transactional
    public void setValue(Long moduleId, Long varId, ValueRequest req) {
        permissionService.requireCanEdit(moduleId);
        Variable v = requireById(varId);
        if (!v.getModuleId().equals(moduleId)) {
            throw BizException.badRequest("变量不属于该模块");
        }
        boolean encrypted = "ENCRYPTED".equals(v.getValueType());

        if ("GLOBAL".equals(req.scope())) {
            String value = req.value();
            if (value == null || value.isEmpty()) {
                // 清空全局值
                lambdaUpdate().eq(Variable::getId, varId).set(Variable::getGlobalValue, null).update();
                return;
            }
            String stored = encrypted ? cryptoService.encrypt(value) : value;
            lambdaUpdate().eq(Variable::getId, varId).set(Variable::getGlobalValue, stored).update();
            return;
        }

        if (!"ENV".equals(req.scope())) {
            throw BizException.badRequest("scope 必须是 GLOBAL 或 ENV");
        }
        if (req.environmentId() == null) {
            throw BizException.badRequest("ENV 作用域必须指定 environmentId");
        }
        String value = req.value();
        if (value == null || value.isEmpty()) {
            // 空值 = 清除该覆盖（§10.5 清空覆盖 → 回退继承）
            clearOverride(varId, req.environmentId(), req.applicationId());
            return;
        }
        String stored = encrypted ? cryptoService.encrypt(value) : value;
        upsertOverride(varId, req.environmentId(), req.applicationId(), stored);
    }

    /**
     * §5.5 DELETE /variables/{varId}/override 清覆盖（回退继承）。
     */
    public void clearOverride(Long varId, Long environmentId, Long applicationId) {
        var w = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VariableOverride>()
                .eq(VariableOverride::getVariableId, varId)
                .eq(VariableOverride::getEnvironmentId, environmentId);
        if (applicationId == null) {
            w.isNull(VariableOverride::getApplicationId);
        } else {
            w.eq(VariableOverride::getApplicationId, applicationId);
        }
        overrideMapper.delete(w);
    }

    private void upsertOverride(Long varId, Long environmentId, Long applicationId, String stored) {
        var q = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VariableOverride>()
                .eq(VariableOverride::getVariableId, varId)
                .eq(VariableOverride::getEnvironmentId, environmentId);
        if (applicationId == null) {
            q.isNull(VariableOverride::getApplicationId);
        } else {
            q.eq(VariableOverride::getApplicationId, applicationId);
        }
        VariableOverride existing = overrideMapper.selectOne(q);
        if (existing == null) {
            VariableOverride o = new VariableOverride();
            o.setVariableId(varId);
            o.setEnvironmentId(environmentId);
            o.setApplicationId(applicationId);
            o.setValue(stored);
            o.setUpdatedBy(SecurityUtils.currentUserId());
            overrideMapper.insert(o);
        } else {
            existing.setValue(stored);
            existing.setUpdatedBy(SecurityUtils.currentUserId());
            overrideMapper.updateById(existing);
        }
    }

    /**
     * §6.4 reveal：返回明文，调用方负责写高危审计。
     */
    public String reveal(Long moduleId, Long varId, Long environmentId) {
        Variable v = requireById(varId);
        if (!v.getModuleId().equals(moduleId)) {
            throw BizException.badRequest("变量不属于该模块");
        }
        if (!"ENCRYPTED".equals(v.getValueType())) {
            throw BizException.badRequest("仅加密变量需要 reveal");
        }
        if (!permissionService.canRevealEncrypted(moduleId, v.getCreatedBy())) {
            throw BizException.forbidden("无权查看该加密变量明文");
        }
        ResolvedValue r = resolver.resolveVariable(v, null, environmentId, true);
        if (!r.isFound()) {
            throw BizException.notFound("该变量在目标环境无值");
        }
        return r.getValue();
    }

    private int nextSortOrder(Long moduleId) {
        Variable last = getBaseMapper().selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Variable>()
                        .select(Variable::getSortOrder)
                        .eq(Variable::getModuleId, moduleId)
                        .orderByDesc(Variable::getSortOrder)
                        .last("limit 1"));
        return last == null || last.getSortOrder() == null ? 0 : last.getSortOrder() + 1;
    }

    /**
     * §5.5 设值请求体。
     */
    public record ValueRequest(String scope, Long environmentId, Long applicationId,
                               String value) {
    }
}
