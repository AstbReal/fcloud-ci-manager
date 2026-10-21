package ci.fcloud.hub.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * SPA fallback：把非 API 的前端路由转发到 index.html（§11.2 nginx try_files 的服务端等价物）。
 *
 * 注意：只匹配不含 "." 的路径（即前端路由），静态资源（/assets/*.js）由 Spring Boot 直接提供。
 *
 * OAuth 回调路径 /oauth/gitlab/callback 的处理见 {@link OAuthCallbackRewriteFilter}。
 */
@Controller
public class SpaFallbackController {

    @RequestMapping(value = {
            "/",
            "/login",
            "/applications",
            "/modules",
            "/modules/{id}",
            "/templates",
            "/settings",
            "/environments",
            "/users",
            "/tokens",
            "/audit",
            "/backups",
            "/profile",
            "/pipeline-docs"
    }, method = {RequestMethod.GET})
    public String index() {
        return "forward:/index.html";
    }
}
