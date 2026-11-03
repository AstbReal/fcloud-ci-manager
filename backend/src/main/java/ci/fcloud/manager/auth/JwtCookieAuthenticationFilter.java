package ci.fcloud.manager.auth;

import ci.fcloud.manager.common.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;

/**
 * 从 Cookie fcloud_token 读取 JWT 并建立 SecurityContext（§6.2）。
 */
@Component
@RequiredArgsConstructor
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "fcloud_token";

    private final TokenBlacklistService tokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 注意：不能只在 authentication == null 时才装载 JWT。
        // OAuth 登录成功后，session 里暂存的是 OAuth2AuthenticationToken
        //（principal 为 OAuth2User，不是 CurrentUser）；浏览器后续请求带
        // JSESSIONID 时会先恢复它。若此时跳过 JWT 装载，console 接口的
        // SecurityUtils.currentUser() 拿不到 CurrentUser，会误判未登录（401）。
        // 因此：只要当前 principal 不是 CurrentUser，就用 JWT Cookie 覆盖。
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        boolean needLoad = existing == null
                || !(existing.getPrincipal() instanceof CurrentUser);
        if (needLoad) {
            String token = resolveToken(request);
            if (StringUtils.hasText(token)) {
                CurrentUser user = tokenService.authenticate(token);
                if (user != null) {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(
                                    user, null, user.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
