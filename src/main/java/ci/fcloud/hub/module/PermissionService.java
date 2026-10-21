package ci.fcloud.hub.module;

import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.common.SecurityUtils;
import org.springframework.stereotype.Service;

/**
 * 权限判定。
 *
 * 模块成员（MODULE_ADMIN / MODULE_OPERATOR）已取消，角色只剩两种：
 * - 普通用户（NONE）：可查看所有模块，可增改变量值、上传文件
 * - 系统管理员（SYSTEM_ADMIN）：额外拥有全部删除权限与加密明文查看权限
 */
@Service
public class PermissionService {

    public boolean isSystemAdmin() {
        return SecurityUtils.isSystemAdmin();
    }

    /** 登录即可查看 */
    public boolean canViewModule(Long moduleId) {
        return SecurityUtils.isAuthenticated();
    }

    /** 登录即可编辑值 / 定义 / 文件 */
    public boolean canEditModule(Long moduleId) {
        return SecurityUtils.isAuthenticated();
    }

    /** 删除只属于系统管理员 */
    public boolean canDelete() {
        return SecurityUtils.isSystemAdmin();
    }

    /**
     * 加密变量明文：系统管理员 ∪ 该变量的创建人本人。
     */
    public boolean canRevealEncrypted(Long moduleId, Long variableCreatedBy) {
        Long uid = SecurityUtils.currentUserId();
        if (uid == null) {
            return false;
        }
        if (isSystemAdmin()) {
            return true;
        }
        return variableCreatedBy != null && variableCreatedBy.equals(uid);
    }

    public void requireCanView(Long moduleId) {
        if (!canViewModule(moduleId)) {
            throw new BizException(40300, "无权访问该模块");
        }
    }

    public void requireCanEdit(Long moduleId) {
        if (!canEditModule(moduleId)) {
            throw new BizException(40300, "无权修改该模块");
        }
    }

    public void requireCanDelete() {
        if (!canDelete()) {
            throw new BizException(40300, "只有系统管理员可以执行删除操作");
        }
    }
}
