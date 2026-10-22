package ci.fcloud.hub.auth;

import ci.fcloud.hub.common.ApiResponse;
import ci.fcloud.hub.common.CurrentUser;
import ci.fcloud.hub.common.SecurityUtils;
import ci.fcloud.hub.user.User;
import ci.fcloud.hub.user.UserService;
import ci.fcloud.hub.user.UserVO;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * §5.1 认证 / 用户（登录用户）
 */
@RestController
@RequestMapping("/api/console/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final TokenBlacklistService blacklistService;

    @GetMapping("/me")
    public ApiResponse<UserVO> me() {
        CurrentUser cu = SecurityUtils.currentUser();
        if (cu == null) {
            return ApiResponse.unauthorized("未登录");
        }
        return ApiResponse.ok(UserVO.of(userService.requireById(cu.getUid())));
    }

    @PutMapping("/me")
    public ApiResponse<UserVO> updateMe(@RequestBody Map<String, String> body) {
        CurrentUser cu = SecurityUtils.currentUser();
        if (cu == null) {
            return ApiResponse.unauthorized("未登录");
        }
        String nickname = body.get("nickname");
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("nickname 不能为空");
        }
        userService.updateNickname(cu.getUid(), nickname.trim());
        return ApiResponse.ok(UserVO.of(userService.requireById(cu.getUid())));
    }

    /**
     * §6.2 注销：清 Cookie + 黑名单（进程内，TTL=剩余有效期）。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (JwtCookieAuthenticationFilter.COOKIE_NAME.equals(c.getName())) {
                    token = c.getValue();
                }
            }
        }
        if (token != null) {
            Claims claims = jwtService.parse(token);
            if (claims != null) {
                blacklistService.blacklist(claims.getId(), jwtService.remainingSeconds(claims));
            }
        }
        Cookie clear = new Cookie(JwtCookieAuthenticationFilter.COOKIE_NAME, "");
        clear.setHttpOnly(true);
        clear.setPath("/");
        clear.setMaxAge(0);
        response.addCookie(clear);
        return ApiResponse.ok();
    }

    /**
     * §5.1 成员选择器用（id/姓名/邮箱/头像）—— 登录即可访问。
     */
    @GetMapping("/user-options")
    public ApiResponse<List<UserVO>> userOptions() {
        List<User> users = userService.lambdaQuery()
                .orderByDesc(User::getCreatedAt)
                .list();
        return ApiResponse.ok(users.stream().map(UserVO::of).toList());
    }
}
