package ci.fcloud.manager.pipeline;

import ci.fcloud.manager.common.LocalCache;
import ci.fcloud.manager.token.ApiToken;
import ci.fcloud.manager.token.ApiTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * §4.1 流水线 API 鉴权。
 *
 * - Authorization: Bearer chub_xxxxxxxx
 * - 401：缺失 / 无效 / 过期 / 吊销
 * - 429：限流（每分钟 N 次，FCLOUD_RATE_LIMIT_PER_MINUTE，默认 60）
 * - 访问日志由 AccessLogFilter / 本过滤器统一记录（§8）
 *
 * 本过滤器只负责鉴权 + 限流，并把 token 放进 request attribute 供下游使用。
 * 注意：任何变量值都不得写入日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_TOKEN = "fcloud.pipeline.token";
    public static final String ATTR_START_MS = "fcloud.pipeline.startMs";

    private static final String BEARER = "Bearer ";
    private static final String RATE_PREFIX = "chub:rate:";

    private final ApiTokenService tokenService;
    private final LocalCache cache;
    private final ObjectMapper objectMapper;
    private final ci.fcloud.manager.config.FcloudProperties props;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只拦截流水线 API；console / actuator / 静态资源不在此处处理
        return !request.getRequestURI().startsWith("/api/v1/pipeline/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(ATTR_START_MS, System.currentTimeMillis());

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            writeError(response, 401, "missing or malformed Authorization header");
            return;
        }
        String plain = header.substring(BEARER.length()).trim();

        Optional<ApiToken> verified = tokenService.verify(plain);
        if (verified.isEmpty()) {
            writeError(response, 401, "invalid, expired or revoked token");
            return;
        }
        ApiToken token = verified.get();

        // §4.1 限流：每 Token 每分钟 N 次
        if (!allow(token)) {
            writeError(response, 429, "rate limit exceeded");
            return;
        }

        request.setAttribute(ATTR_TOKEN, token);
        // 记录最近使用时间（尽力而为，不阻塞主流程）
        try {
            tokenService.touch(token.getId());
        } catch (Exception ignored) {
            // 忽略：last_used_at 不影响鉴权
        }

        chain.doFilter(request, response);
    }

    /**
     * 固定窗口计数：key=chub:rate:{tokenId}:{epochMinute}，TTL 120 秒。
     */
    private boolean allow(ApiToken token) {
        long perMinute = props.getRateLimit().getPerMinute();
        if (perMinute <= 0) {
            return true;
        }
        long minute = System.currentTimeMillis() / 60000L;
        String key = RATE_PREFIX + token.getId() + ":" + minute;
        long n = cache.increment(key, Duration.ofSeconds(120));
        return n <= perMinute;
    }

    private void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), java.util.Map.of("error", message));
    }
}
