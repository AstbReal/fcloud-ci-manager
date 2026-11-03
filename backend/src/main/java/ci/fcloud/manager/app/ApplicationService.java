package ci.fcloud.manager.app;

import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.common.SecurityUtils;
import ci.fcloud.manager.module.Module;
import ci.fcloud.manager.module.ModuleMapper;
import ci.fcloud.manager.module.ModuleSubscription;
import ci.fcloud.manager.module.ModuleSubscriptionMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * §5.3 应用 CRUD。写操作仅系统管理员。
 */
@Service
@RequiredArgsConstructor
public class ApplicationService extends ServiceImpl<ApplicationMapper, Application> {

    private final ModuleMapper moduleMapper;
    private final ModuleSubscriptionMapper subscriptionMapper;

    public List<Application> listAll() {
        return lambdaQuery().orderByAsc(Application::getSortOrder)
                .orderByAsc(Application::getId).list();
    }

    public Application requireById(Long id) {
        Application a = getById(id);
        if (a == null) {
            throw BizException.notFound("应用不存在: " + id);
        }
        return a;
    }

    public Application requireByCode(String code) {
        Application a = lambdaQuery().eq(Application::getCode, code).one();
        if (a == null) {
            throw BizException.badRequest("应用不存在: " + code);
        }
        return a;
    }

    /**
     * 从 GitLab 项目导入应用：code 缺省时由项目名推导。
     */
    public Application importFromGitLab(Long projectId, String code, String name,
                                        String description, String repoUrl) {
        if (projectId == null) {
            throw BizException.badRequest("projectId 不能为空");
        }
        if (lambdaQuery().eq(Application::getGitlabProjectId, projectId).count() > 0) {
            throw BizException.conflict("该项目已经导入过，请直接在看板中查看");
        }
        Application a = new Application();
        String finalCode = code == null || code.isBlank() ? slug(name) : code.trim();
        a.setCode(finalCode);
        a.setName(name == null || name.isBlank() ? finalCode : name.trim());
        a.setDescription(description);
        a.setRepoUrl(repoUrl);
        a.setGitlabProjectId(projectId);
        return create(a);
    }

    private static String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "app-" + System.currentTimeMillis();
        }
        String s = raw.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.isEmpty()) {
            s = "app-" + System.currentTimeMillis();
        }
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    public Application create(Application body) {
        if (body.getCode() == null || body.getCode().isBlank()) {
            throw BizException.badRequest("code 不能为空");
        }
        if (body.getName() == null || body.getName().isBlank()) {
            throw BizException.badRequest("name 不能为空");
        }
        if (lambdaQuery().eq(Application::getCode, body.getCode()).count() > 0) {
            throw BizException.conflict("应用 code 已存在: " + body.getCode());
        }
        Application a = new Application();
        a.setCode(body.getCode());
        a.setName(body.getName());
        a.setDescription(body.getDescription());
        a.setSortOrder(body.getSortOrder() == null ? 0 : body.getSortOrder());
        a.setCreatedBy(SecurityUtils.currentUserId());
        save(a);
        return a;
    }

    public Application update(Long id, Application body) {
        Application a = requireById(id);
        if (body.getCode() != null && !body.getCode().equals(a.getCode())) {
            long dup = lambdaQuery().eq(Application::getCode, body.getCode())
                    .ne(Application::getId, id).count();
            if (dup > 0) {
                throw BizException.conflict("应用 code 已存在: " + body.getCode());
            }
            a.setCode(body.getCode());
        }
        if (body.getName() != null) {
            a.setName(body.getName());
        }
        if (body.getDescription() != null) {
            a.setDescription(body.getDescription());
        }
        if (body.getSortOrder() != null) {
            a.setSortOrder(body.getSortOrder());
        }
        updateById(a);
        return a;
    }

    /**
     * §5.3 删除应用需输入应用名确认；级联删模块/Token/订阅（schema ON DELETE CASCADE）。
     */
    @Transactional
    public void delete(Long id, String confirmName) {
        Application a = requireById(id);
        if (confirmName == null || !confirmName.equals(a.getCode())) {
            throw BizException.badRequest("确认名称不匹配，需输入应用 code: " + a.getCode());
        }
        // 显式清理订阅关系（schema 也会级联，这里显式化便于审计）
        List<Module> privateModules = moduleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Module>()
                        .eq(Module::getApplicationId, id));
        for (Module m : privateModules) {
            subscriptionMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleSubscription>()
                            .eq(ModuleSubscription::getModuleId, m.getId()));
        }
        subscriptionMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModuleSubscription>()
                        .eq(ModuleSubscription::getApplicationId, id));
        removeById(id);
    }

    public void sort(List<Long> ids) {
        if (ids == null) {
            return;
        }
        int order = 0;
        for (Long id : ids) {
            lambdaUpdate().eq(Application::getId, id)
                    .set(Application::getSortOrder, order++).update();
        }
    }
}
