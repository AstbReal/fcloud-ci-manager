package ci.fcloud.manager.config;

import ci.fcloud.manager.auth.JwtCookieAuthenticationFilter;
import ci.fcloud.manager.auth.OAuth2LoginSuccessHandler;
import ci.fcloud.manager.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * §6.1/§6.2 安全配置。
 * - GitLab OAuth 登录入口：/oauth2/authorization/gitlab；回调 /oauth/gitlab/callback
 * - 会话：httpOnly Cookie + JWT；SameSite=Lax；Secure 可配置
 * - 删除操作一律仅系统管理员；/api/console/admin/** 下的读取与编辑对登录用户开放，
 *   其余（用户、Token、审计、备份、系统设置）仍仅系统管理员
 * - /api/v1/pipeline/** 由 PipelineTokenFilter 单独鉴权（§4.1）
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter;
    private final ci.fcloud.manager.pipeline.PipelineAuthFilter pipelineAuthFilter;
    private final ci.fcloud.manager.mcp.McpAuthFilter mcpAuthFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           OAuth2LoginSuccessHandler successHandler) throws Exception {
        http
                .csrf().disable()
                .cors().and()
                // OAuth2 授权码流程需要在会话中暂存 authorizationRequest（含 state 校验），
                // 因此不能全局 STATELESS；登录完成后业务请求仍走 JWT Cookie。
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .and()
                .authorizeRequests()
                // 静态资源（SPA）
                .antMatchers("/", "/index.html", "/favicon.ico", "/assets/**").permitAll()
                // OAuth 登录入口与回调（§6.1：/oauth2/authorization/gitlab、/oauth/gitlab/callback）
                .antMatchers("/oauth2/**", "/oauth/gitlab/callback",
                        "/login/**", "/error").permitAll()
                // 健康检查与文档
                .antMatchers("/actuator/health", "/actuator/health/**",
                        "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**",
                        "/swagger-ui.html").permitAll()
                // 流水线 API：Token 鉴权在过滤器内完成，这里先放行
                .antMatchers("/api/v1/pipeline/**").permitAll()
                // MCP：Bearer 鉴权在 McpAuthFilter 内完成，这里先放行
                .antMatchers("/mcp").permitAll()
                // 订阅关系是「关系」不是「元素」：登录用户可订阅/取消订阅
                .antMatchers("/api/console/admin/applications/*/subscriptions/**")
                .authenticated()
                // 删除一律仅系统管理员（先于下面的放行规则匹配）
                .antMatchers(HttpMethod.DELETE, "/api/console/admin/**")
                .hasRole("SYSTEM_ADMIN")
                // 普通用户可读的基础数据：环境、模板、模块、应用
                .antMatchers(HttpMethod.GET,
                        "/api/console/admin/environments/**",
                        "/api/console/admin/templates/**",
                        "/api/console/admin/modules/**",
                        "/api/console/admin/applications/**")
                .authenticated()
                // 普通用户可编辑模块（新建 / 改名 / 分组 / 复制 / 在应用下建模块）
                .antMatchers(HttpMethod.POST,
                        "/api/console/admin/modules/**",
                        "/api/console/admin/shared-modules",
                        "/api/console/admin/applications/*/modules")
                .authenticated()
                .antMatchers(HttpMethod.PUT, "/api/console/admin/modules/**").authenticated()
                // 其余 admin 端点（用户、Token、审计、备份、系统设置、环境写操作）仍仅系统管理员
                .antMatchers("/api/console/admin/**").hasRole("SYSTEM_ADMIN")
                // 其余 console API 需登录
                .antMatchers("/api/console/**").authenticated()
                .anyRequest().permitAll()
                .and()
                .oauth2Login()
                .successHandler(successHandler)
                .and()
                .logout().disable()
                // 认证/授权失败统一返回 ApiResponse JSON（§5 响应包装）
                .exceptionHandling()
                .authenticationEntryPoint(this::write401)
                .accessDeniedHandler(this::write403);

        http.addFilterBefore(pipelineAuthFilter,
                UsernamePasswordAuthenticationFilter.class);
        // MCP 过滤器必须只挂在 Security 链里（见下方 FilterRegistrationBean 关闭自动注册），
        // 否则它会在 SecurityContextPersistenceFilter 之前执行，主体被覆盖。
        http.addFilterBefore(mcpAuthFilter,
                UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(jwtCookieAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 关闭 McpAuthFilter 的 Servlet 自动注册：它只应作为 Security 链的一环执行一次。
     * 若同时被容器注册，OncePerRequestFilter 的“已执行”标记会让链内那次被跳过，
     * 导致 SecurityContext 被 SecurityContextPersistenceFilter 清空。
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<ci.fcloud.manager.mcp.McpAuthFilter>
    mcpAuthFilterRegistration(ci.fcloud.manager.mcp.McpAuthFilter filter) {
        var registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    private void write401(HttpServletRequest req, HttpServletResponse resp, Exception e)
            throws IOException {
        writeJson(resp, 401, ApiResponse.unauthorized("未登录或会话已过期"));
    }

    private void write403(HttpServletRequest req, HttpServletResponse resp, Exception e)
            throws IOException {
        writeJson(resp, 403, ApiResponse.error(40300, "无权限执行该操作"));
    }

    private void writeJson(HttpServletResponse resp, int status, ApiResponse<Void> body)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(resp.getWriter(), body);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOriginPatterns(List.of("*"));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setAllowCredentials(true);
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }
}
