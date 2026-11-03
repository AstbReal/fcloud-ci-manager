package ci.fcloud.manager.env;

import ci.fcloud.manager.common.BizException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * §5.2 环境 CRUD + 继承链（含防环）。
 *
 * §2.2：父链遍历需防环；环境配置时也拒绝成环。
 */
@Service
@RequiredArgsConstructor
public class EnvironmentService extends ServiceImpl<EnvironmentMapper, Environment> {

    public List<Environment> listAll() {
        return lambdaQuery().orderByAsc(Environment::getSortOrder)
                .orderByAsc(Environment::getId).list();
    }

    public Environment requireById(Long id) {
        Environment e = getById(id);
        if (e == null) {
            throw BizException.notFound("环境不存在: " + id);
        }
        return e;
    }

    public Environment requireByCode(String code) {
        Environment e = lambdaQuery().eq(Environment::getCode, code).one();
        if (e == null) {
            throw BizException.badRequest("环境不存在: " + code);
        }
        return e;
    }

    public Environment create(Environment body, Long operatorId) {
        validate(body);
        if (lambdaQuery().eq(Environment::getCode, body.getCode()).count() > 0) {
            throw BizException.conflict("环境 code 已存在: " + body.getCode());
        }
        // 新建环境不允许把父环境设成自己（此时还没有 id）
        Environment e = new Environment();
        e.setCode(body.getCode());
        e.setName(body.getName());
        e.setColor(body.getColor() == null ? "#2563eb" : body.getColor());
        e.setSortOrder(body.getSortOrder() == null ? 0 : body.getSortOrder());
        e.setIsDefault(Boolean.TRUE.equals(body.getIsDefault()));
        e.setParentEnvId(body.getParentEnvId());
        save(e);
        if (body.getParentEnvId() != null) {
            assertNoCycle(e.getId(), body.getParentEnvId());
        }
        if (Boolean.TRUE.equals(body.getIsDefault())) {
            clearOtherDefaults(e.getId());
        }
        return e;
    }

    public Environment update(Long id, Environment body) {
        Environment e = requireById(id);
        if (body.getCode() != null && !body.getCode().equals(e.getCode())) {
            long dup = lambdaQuery().eq(Environment::getCode, body.getCode())
                    .ne(Environment::getId, id).count();
            if (dup > 0) {
                throw BizException.conflict("环境 code 已存在: " + body.getCode());
            }
            e.setCode(body.getCode());
        }
        if (body.getName() != null) {
            e.setName(body.getName());
        }
        if (body.getColor() != null) {
            e.setColor(body.getColor());
        }
        if (body.getSortOrder() != null) {
            e.setSortOrder(body.getSortOrder());
        }
        // parent_env_id 显式传入 null 表示"设为顶层"；不传（null）表示不修改。
        // 用 body 的 isDefault 标志位区分不可行，因此约定：前端更新继承关系时必须显式传 parentEnvId
        // （包括传 null 表示解除继承）。这里只在该字段被设置时处理。
        if (body.isParentEnvIdSet()) {
            if (body.getParentEnvId() != null && body.getParentEnvId().equals(id)) {
                throw BizException.badRequest("父环境不能是环境自身");
            }
            assertNoCycle(id, body.getParentEnvId());
            e.setParentEnvId(body.getParentEnvId());
        }
        if (body.getIsDefault() != null) {
            e.setIsDefault(body.getIsDefault());
            if (Boolean.TRUE.equals(body.getIsDefault())) {
                clearOtherDefaults(id);
            }
        }
        updateById(e);
        return e;
    }

    @Transactional
    public void delete(Long id) {
        requireById(id);
        // 级联清绑定/覆盖/文件由 schema 的 ON DELETE CASCADE 完成；
        // 这里显式删除子环境指向该父环境的引用，避免悬空
        lambdaUpdate().eq(Environment::getParentEnvId, id)
                .set(Environment::getParentEnvId, null).update();
        removeById(id);
    }

    public void sort(List<Long> ids) {
        if (ids == null) {
            return;
        }
        int order = 0;
        for (Long id : ids) {
            lambdaUpdate().eq(Environment::getId, id)
                    .set(Environment::getSortOrder, order++).update();
        }
    }

    /**
     * §2.2 防环：把 parentEnvId 设为 proposedParent 后，从 proposedParent 沿父链向上
     * 不应再遇到 envId 自身。
     */
    public void assertNoCycle(Long envId, Long proposedParent) {
        if (proposedParent == null) {
            return;
        }
        if (proposedParent.equals(envId)) {
            throw BizException.badRequest("不允许设置环境继承环（父环境不能是自身）");
        }
        Set<Long> visited = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(proposedParent);
        while (!stack.isEmpty()) {
            Long cur = stack.pop();
            if (cur == null || !visited.add(cur)) {
                continue;
            }
            if (cur.equals(envId)) {
                throw BizException.badRequest(
                        "不允许设置环境继承环（" + envId + " 的祖先链包含自身）");
            }
            Environment e = getById(cur);
            if (e != null && e.getParentEnvId() != null) {
                stack.push(e.getParentEnvId());
            }
        }
    }

    private void clearOtherDefaults(Long keepId) {
        lambdaUpdate().ne(Environment::getId, keepId)
                .eq(Environment::getIsDefault, true)
                .set(Environment::getIsDefault, false)
                .update();
    }

    private void validate(Environment body) {
        if (body.getCode() == null || body.getCode().isBlank()) {
            throw BizException.badRequest("code 不能为空");
        }
        if (body.getName() == null || body.getName().isBlank()) {
            throw BizException.badRequest("name 不能为空");
        }
        if (body.getParentEnvId() != null && getById(body.getParentEnvId()) == null) {
            throw BizException.badRequest("父环境不存在: " + body.getParentEnvId());
        }
    }
}
