package ci.fcloud.hub.user;

import ci.fcloud.hub.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * §5.1 用户管理（仅系统管理员，§6.3 权限矩阵）。
 * 路径前缀 /api/console/admin/users —— 由 SecurityConfig 限定 hasRole(SYSTEM_ADMIN)。
 */
@RestController
@RequestMapping("/api/console/admin/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final ci.fcloud.hub.module.ModuleMemberMapper moduleMemberMapper;

    private UserVO toVO(User u) {
        UserVO vo = UserVO.of(u);
        vo.setGitlabId(u.getGitlabId());
        vo.setLastLoginAt(u.getLastLoginAt());
        vo.setMemberCount(moduleMemberMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ci.fcloud.hub.module.ModuleMember>()
                        .eq(ci.fcloud.hub.module.ModuleMember::getUserId, u.getId())).intValue());
        return vo;
    }

    @GetMapping
    public ApiResponse<List<UserVO>> list(@RequestParam(required = false) String keyword) {
        List<User> users;
        if (keyword == null || keyword.isBlank()) {
            users = userService.lambdaQuery().orderByDesc(User::getCreatedAt).list();
        } else {
            users = userService.lambdaQuery()
                    .and(w -> w.like(User::getEmail, keyword)
                            .or().like(User::getName, keyword)
                            .or().like(User::getNickname, keyword))
                    .orderByDesc(User::getCreatedAt)
                    .list();
        }
        return ApiResponse.ok(users.stream().map(this::toVO).toList());
    }

    @PutMapping("/{id}/role")
    public ApiResponse<UserVO> setRole(@org.springframework.web.bind.annotation.PathVariable Long id,
                                       @RequestBody Map<String, String> body) {
        String role = body.get("role");
        GlobalRole gr;
        try {
            gr = GlobalRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("role 必须是 SYSTEM_ADMIN 或 NONE");
        }
        userService.setGlobalRole(id, gr);
        return ApiResponse.ok(UserVO.of(userService.requireById(id)));
    }
}
