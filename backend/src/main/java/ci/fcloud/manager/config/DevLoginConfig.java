package ci.fcloud.manager.config;

import ci.fcloud.manager.auth.JwtCookieAuthenticationFilter;
import ci.fcloud.manager.auth.JwtService;
import ci.fcloud.manager.user.User;
import ci.fcloud.manager.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 本地开发/测试用的"模拟 OAuth 登录"入口（默认关闭）。
 * 仅在 fcloud.dev-login-enabled=true 时注册；生产镜像不设置该变量。
 *
 * GET /dev-login?email=..&name=..&username=..&gitlabId=..&remember=1
 * 直接按 GitLab 用户属性建号/登录，写 Cookie 后 302 到 /。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "fcloud.dev-login-enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevLoginConfig {

    private final UserService userService;
    private final JwtService jwtService;

    @Bean
    @Order(1)
    public SecurityFilterChain devLoginChain(HttpSecurity http) throws Exception {
        http.antMatcher("/dev-login/**")
                .csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeRequests().anyRequest().permitAll();
        return http.build();
    }

    /**
     * 用 servlet 注册而非 @RestController，避免与 console 安全链耦合。
     */
    @Bean
    public javax.servlet.http.HttpServlet devLoginServlet() {
        return new javax.servlet.http.HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException {
                boolean remember = "1".equals(req.getParameter("remember"));
                String email = orDefault(req.getParameter("email"), "dev@example.com");
                String name = orDefault(req.getParameter("name"), "Dev User");
                String username = orDefault(req.getParameter("username"), "dev");
                String gitlabId = orDefault(req.getParameter("gitlabId"),
                        String.valueOf(Math.abs(email.hashCode())));

                User u = userService.findOrCreate(gitlabId, email, name, username, null);
                String token = jwtService.issue(u.getId(), u.getEmail(), u.getName(),
                        u.getGlobalRole(), remember);
                Cookie cookie = new Cookie(JwtCookieAuthenticationFilter.COOKIE_NAME, token);
                cookie.setHttpOnly(true);
                cookie.setPath("/");
                cookie.setMaxAge((int) jwtService.ttlSeconds(remember));
                resp.addCookie(cookie);
                log.info("dev-login: email={} role={}", email, u.getGlobalRole());
                resp.sendRedirect("/");
            }

            private String orDefault(String v, String d) {
                return v == null || v.isBlank() ? d : v;
            }
        };
    }

    /**
     * 把上面的 servlet 映射到 /dev-login。
     */
    @Bean
    public ServletRegistrationBean<javax.servlet.http.HttpServlet>
    devLoginRegistration(javax.servlet.http.HttpServlet devLoginServlet) {
        ServletRegistrationBean<javax.servlet.http.HttpServlet> b =
                new ServletRegistrationBean<>(devLoginServlet, "/dev-login");
        b.setName("devLoginServlet");
        return b;
    }
}
