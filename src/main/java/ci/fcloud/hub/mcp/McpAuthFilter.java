package ci.fcloud.hub.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ci.fcloud.hub.common.CurrentUser;
import ci.fcloud.hub.config.FcloudProperties;
import ci.fcloud.hub.pat.PatService;
import ci.fcloud.hub.token.ApiToken;
import ci.fcloud.hub.token.ApiTokenService;
import ci.fcloud.hub.user.GlobalRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /mcp 鉴权过滤器。
 *
 * <p>两类凭证，主体不同，能力不同：</p>
 * <ul>
 *   <li>{@code chub_pat_*}：个人访问令牌，映射到真实用户 + scope，
 *       审计能追到人；</li>
 *   <li>{@code chub_*}：流水线 Token，映射为只读机器主体（uid=0），
 *       只能调用只读工具。</li>
 * </ul>
 *
 * <p>注意：本过滤器由 SecurityConfig 通过 addFilterBefore 挂载，
 * 同时用 FilterRegistrationBean 关闭了 Spring Boot 的自动注册——
 * 否则它会在 Security 链之外先执行一次，SecurityContext 会被
 * SecurityContextPersistenceFilter 覆盖，导致下游拿不到主体。</p>
 */
@Component
@RequiredArgsConstructor
public class McpAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_PRINCIPAL = "fcloud.mcp.principal";

    private static final String BEARER = "Bearer ";
    private static final String RATE_PREFIX = "chub:rate:mcp:";

    private final ApiTokenService apiTokenService;
    private final McpSettingService mcpSettings;
    private final PatService patService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FcloudProperties props;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !("/mcp".equals(uri) || uri.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!mcpSettings.enabled()) {
            writeError(response, 404, "mcp disabled");
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            unauthorized(response, "missing or malformed Authorization header");
            return;
        }
        String plain = header.substring(BEARER.length()).trim();

        McpPrincipal principal;
        CurrentUser user;

        if (plain.startsWith(PatService.TOKEN_PREFIX)) {
            Optional<PatService.Verified> verified = patService.verify(plain);
            if (verified.isEmpty()) {
                unauthorized(response, "invalid, expired or revoked personal access token");
                return;
            }
            PatService.Verified v = verified.get();
            if (!allow("pat:" + v.pat().getId())) {
                writeError(response, 429, "rate limit exceeded");
                return;
            }
            touchSafely(() -> patService.touch(v.pat().getId()));
            principal = McpPrincipal.user(v.user().getUid(), v.user().getEmail(),
                    v.scopes(), v.pat().getId());
            user = v.user();
        } else {
            Optional<ApiToken> verified = apiTokenService.verify(plain);
            if (verified.isEmpty()) {
                unauthorized(response, "invalid, expired or revoked token");
                return;
            }
            ApiToken token = verified.get();
            if (!allow("token:" + token.getId())) {
                writeError(response, 429, "rate limit exceeded");
                return;
            }
            touchSafely(() -> apiTokenService.touch(token.getId()));
            principal = McpPrincipal.machine(token.getId(), token.getTokenPrefix());
            // 机器主体不是真实用户：uid=0 明确表示“非人类”，权限只有 ROLE_NONE，
            // 写工具在 ToolRegistry 层就已被 scope 挡掉。
            user = new CurrentUser(0L, "mcp@fcloud.local", principal.label(),
                    GlobalRole.NONE, null, null);
        }

        request.setAttribute(ATTR_PRINCIPAL, principal);
        bindSecurityContext(request, user);
        chain.doFilter(request, response);
    }

    private void bindSecurityContext(HttpServletRequest request, CurrentUser user) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void touchSafely(Runnable r) {
        try {
            r.run();
        } catch (Exception ignored) {
            // last_used_at 不影响鉴权
        }
    }

    private boolean allow(String keyPart) {
        long perMinute = props.getMcp().getRatePerMinute();
        if (perMinute <= 0) {
            return true;
        }
        long minute = System.currentTimeMillis() / 60000L;
        String key = RATE_PREFIX + keyPart + ":" + minute;
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1L) {
            redis.expire(key, Duration.ofSeconds(120));
        }
        return n == null || n <= perMinute;
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        writeError(response, 401, message);
    }

    private void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("error", message));
    }
}
