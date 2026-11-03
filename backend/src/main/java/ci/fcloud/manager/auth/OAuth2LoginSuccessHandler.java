package ci.fcloud.manager.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * §6.1 登录成功：签发 JWT → 写 httpOnly Cookie → 302 到 /。
 *
 * GitLab 用户信息映射（§6.1）：
 *   email（唯一键，去重）、name（姓名）、username → nickname、avatar_url、id → gitlab_id
 * email 为 null 时（GitLab 隐私设置）：回退 {username}@{gitlab host 的域名}，并记录日志。
 *
 * "记住我"：通过 /oauth2/authorization/gitlab?remember=1 传参（§6.2）。
 */
@Slf4j
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtService jwtService;
    private final ci.fcloud.manager.user.UserService userService;
    private final GitLabUserMapper gitLabUserMapper;

    public OAuth2LoginSuccessHandler(JwtService jwtService,
                                     ci.fcloud.manager.user.UserService userService,
                                     GitLabUserMapper gitLabUserMapper) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.gitLabUserMapper = gitLabUserMapper;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication)
            throws ServletException, IOException {
        OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
        OAuth2User principal = oauth.getPrincipal();

        String gitlabId = str(principal.getAttribute("id"));
        String name = str(principal.getAttribute("name"));
        String username = str(principal.getAttribute("username"));
        String email = str(principal.getAttribute("email"));
        String avatar = str(principal.getAttribute("avatar_url"));

        // §6.1：email 为 null 时回退 {username}@{gitlab host 的域名}，并记录日志
        boolean emailFallback = email == null || email.isBlank();
        if (emailFallback) {
            email = gitLabUserMapper.resolveEmail(email, username);
            log.warn("GitLab user {} (id={}) 返回空 email，已回退为 {}", username, gitlabId, email);
        }

        boolean remember = "1".equals(request.getParameter("remember"));
        ci.fcloud.manager.user.User user = userService.findOrCreate(
                gitlabId, email, name, username, avatar);

        String token = jwtService.issue(user.getId(), user.getEmail(), user.getName(),
                user.getGlobalRole(), remember);

        // §6.2：JWT 放 httpOnly + SameSite=Lax + Secure（可配置）Cookie，名 fcloud_token。
        // 注意：javax.servlet.http.Cookie 没有 setSameSite，用 setComment 是错的；
        // 改用 Spring 的 ResponseCookie 正确设置 SameSite=Lax。
        //
        // SameSite=Lax 是 §6.2 规定的正确生产配置（HTTPS 同域）。
        // 本地开发若用不同端口跑 GitLab 与主程序，会因 Chrome 的
        // "site for cookies" 机制导致登录后 XHR 不带 cookie（见 README D26），
        // 因此本地联调请把 GitLab 反代到主程序同域（见 .ci/gitlab-lite）。
        org.springframework.http.ResponseCookie cookie =
                org.springframework.http.ResponseCookie.from(
                        JwtCookieAuthenticationFilter.COOKIE_NAME, token)
                        .httpOnly(true)
                        .path("/")
                        .maxAge(jwtService.ttlSeconds(remember))
                        .secure(cookieSecure())
                        .sameSite(org.springframework.boot.web.server.Cookie.SameSite.LAX.attributeValue())
                        .build();
        response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE, cookie.toString());

        response.sendRedirect("/");
    }

    private boolean cookieSecure() {
        String v = System.getenv("FCLOUD_COOKIE_SECURE");
        return v != null && ("true".equalsIgnoreCase(v) || "1".equals(v));
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
