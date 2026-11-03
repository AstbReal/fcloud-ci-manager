package ci.fcloud.manager.module;

import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.common.CurrentUser;
import ci.fcloud.manager.user.GlobalRole;
import ci.fcloud.manager.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.3 权限矩阵单测。
 *
 * | 能力              | 系统管理员 | 模块管理员 | 模块操作员 | 无权限用户 |
 * | 查看模块配置        |     ✓    |     ✓    |     ✓    |     ✗    |
 * | 查看他人加密变量明文  |     ✓    |     ✓    |     ✗    |     ✗    |
 * | 增删改变量 / 文件   |     ✓    |     ✓    |     ✓    |     ✗    |
 * | 管理模块成员        |     ✓    |     ✓    |     ✗    |     ✗    |
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionServiceTest {

    private static final long MODULE = 500L;
    private static final long MODULE_B = 502L;
    private static final long ADMIN = 1L;
    private static final long OPERATOR = 2L;
    private static final long OUTSIDER = 3L;

    @Mock
    private UserService userService;

    @InjectMocks
    private PermissionService permissionService;

    /** (moduleId,userId) → 成员，模拟"按模块独立授权"的存储 */
    private final Map<String, ModuleMember> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        store.clear();
        permissionService.setMembershipLookupForTest((moduleId, userId) -> {
            ModuleMember m = store.get(moduleId + ":" + userId);
            return m == null ? null : m;
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void grant(long moduleId, long userId, ModuleRole role) {
        ModuleMember m = new ModuleMember();
        m.setId(moduleId + userId);
        m.setModuleId(moduleId);
        m.setUserId(userId);
        m.setRole(role.name());
        store.put(moduleId + ":" + userId, m);
    }

    private void loginAs(Long uid, GlobalRole role) {
        CurrentUser cu = new CurrentUser(uid, uid + "@x.com", "U" + uid, role, "n", null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(cu, null, cu.getAuthorities()));
    }

    // ---------------- 系统管理员 ----------------

    @Test
    @DisplayName("§6.3 系统管理员可见/可改任意模块，即使不是成员")
    void systemAdminHasAllModulePowers() {
        loginAs(999L, GlobalRole.SYSTEM_ADMIN);
        // 无任何成员记录

        assertTrue(permissionService.canViewModule(MODULE));
        assertTrue(permissionService.canEditModule(MODULE));
        assertTrue(permissionService.canManageMembers(MODULE));
        assertEquals(ModuleRole.MODULE_ADMIN, permissionService.moduleRole(MODULE));
        assertTrue(permissionService.canRevealEncrypted(MODULE, OPERATOR));
    }

    // ---------------- 模块管理员 ----------------

    @Test
    @DisplayName("§6.3 模块管理员：查看✓ 加密明文✓ 增删改✓ 管理成员✓")
    void moduleAdminPowers() {
        loginAs(ADMIN, GlobalRole.NONE);
        grant(MODULE, ADMIN, ModuleRole.MODULE_ADMIN);

        assertTrue(permissionService.canViewModule(MODULE));
        assertTrue(permissionService.canEditModule(MODULE));
        assertTrue(permissionService.canManageMembers(MODULE));
        assertTrue(permissionService.canRevealEncrypted(MODULE, OPERATOR),
                "模块管理员可查看他人加密变量明文");
        assertEquals(ModuleRole.MODULE_ADMIN, permissionService.moduleRole(MODULE));
    }

    // ---------------- 模块操作员 ----------------

    @Test
    @DisplayName("§6.3 模块操作员：查看✓ 增删改✓ 但不可看他人加密明文、不可管理成员")
    void moduleOperatorPowers() {
        loginAs(OPERATOR, GlobalRole.NONE);
        grant(MODULE, OPERATOR, ModuleRole.MODULE_OPERATOR);

        assertTrue(permissionService.canViewModule(MODULE));
        assertTrue(permissionService.canEditModule(MODULE), "操作员可增删改变量/文件");

        assertFalse(permissionService.canManageMembers(MODULE), "操作员不可管理成员");
        assertFalse(permissionService.canRevealEncrypted(MODULE, ADMIN),
                "操作员不可查看他人加密变量明文");
        assertEquals(ModuleRole.MODULE_OPERATOR, permissionService.moduleRole(MODULE));
    }

    @Test
    @DisplayName("§6.4 操作员可读取自己创建的加密变量明文")
    void operatorCanReadOwnEncryptedVariable() {
        loginAs(OPERATOR, GlobalRole.NONE);
        grant(MODULE, OPERATOR, ModuleRole.MODULE_OPERATOR);

        assertTrue(permissionService.canRevealEncrypted(MODULE, OPERATOR),
                "创建人本人可读自己的加密变量");
    }

    // ---------------- 无权限用户 ----------------

    @Test
    @DisplayName("§6.3 无权限用户（NONE 且非成员）：全部✗")
    void outsiderHasNoPowers() {
        loginAs(OUTSIDER, GlobalRole.NONE);
        grant(MODULE, ADMIN, ModuleRole.MODULE_ADMIN);
        grant(MODULE, OPERATOR, ModuleRole.MODULE_OPERATOR);

        assertFalse(permissionService.canViewModule(MODULE));
        assertFalse(permissionService.canEditModule(MODULE));
        assertFalse(permissionService.canManageMembers(MODULE));
        assertFalse(permissionService.canRevealEncrypted(MODULE, ADMIN));
        assertNull(permissionService.moduleRole(MODULE));
    }

    @Test
    @DisplayName("§6.3 未登录用户：全部✗")
    void anonymousHasNoPowers() {
        grant(MODULE, ADMIN, ModuleRole.MODULE_ADMIN);

        assertFalse(permissionService.canViewModule(MODULE));
        assertFalse(permissionService.canEditModule(MODULE));
        assertFalse(permissionService.canManageMembers(MODULE));
        assertFalse(permissionService.canRevealEncrypted(MODULE, null));
        assertNull(permissionService.moduleRole(MODULE));
    }

    // ---------------- 模块可见性（§6.3 末条） ----------------

    @Test
    @DisplayName("§6.3 模块成员按模块独立授权：A 模块成员 ≠ B 模块成员")
    void membershipIsPerModule() {
        loginAs(ADMIN, GlobalRole.NONE);
        grant(MODULE, ADMIN, ModuleRole.MODULE_ADMIN);
        // MODULE_B 未授权给 ADMIN

        assertTrue(permissionService.canViewModule(MODULE));
        assertFalse(permissionService.canViewModule(MODULE_B),
                "仅 A 模块成员不应看到 B 模块");
    }

    // ---------------- 越权时抛出 403 ----------------

    @Test
    @DisplayName("§6.3 requireCanEdit 对无权限用户抛 403")
    void requireCanEditThrowsForbidden() {
        loginAs(OUTSIDER, GlobalRole.NONE);
        grant(MODULE, ADMIN, ModuleRole.MODULE_ADMIN);

        BizException ex = assertThrows(BizException.class,
                () -> permissionService.requireCanEdit(MODULE));
        assertEquals(40300, ex.getCode());
    }

    @Test
    @DisplayName("§6.3 requireCanManageMembers 对操作员抛 403")
    void requireCanManageMembersThrowsForOperator() {
        loginAs(OPERATOR, GlobalRole.NONE);
        grant(MODULE, OPERATOR, ModuleRole.MODULE_OPERATOR);

        assertThrows(BizException.class,
                () -> permissionService.requireCanManageMembers(MODULE));
    }

    @Test
    @DisplayName("§6.3 requireCanEdit 对模块管理员通过")
    void requireCanEditPassesForModuleAdmin() {
        loginAs(ADMIN, GlobalRole.NONE);
        grant(MODULE, ADMIN, ModuleRole.MODULE_ADMIN);

        assertDoesNotThrow(() -> permissionService.requireCanEdit(MODULE));
    }

    @Test
    @DisplayName("§6.3 roleOf 对未知角色字符串返回 null")
    void unknownRoleTreatedAsNoRole() {
        loginAs(ADMIN, GlobalRole.NONE);
        ModuleMember bad = new ModuleMember();
        bad.setModuleId(MODULE);
        bad.setUserId(ADMIN);
        bad.setRole("WEIRD_ROLE");
        store.put(MODULE + ":" + ADMIN, bad);

        assertNull(permissionService.moduleRole(MODULE));
    }
}
