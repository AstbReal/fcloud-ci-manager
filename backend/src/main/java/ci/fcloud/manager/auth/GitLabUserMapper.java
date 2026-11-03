package ci.fcloud.manager.auth;

import ci.fcloud.manager.config.FcloudProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * §6.1 GitLab 用户信息映射辅助：email 为 null 时回退 {username}@{gitlab host 的域名}。
 */
@Component
public class GitLabUserMapper {

    private final FcloudProperties props;

    public GitLabUserMapper(FcloudProperties props) {
        this.props = props;
    }

    public String resolveEmail(String email, String username) {
        if (email != null && !email.isBlank()) {
            return email;
        }
        String host = gitlabHost();
        String fallback = (username == null || username.isBlank() ? "user" : username) + "@" + host;
        // 文档要求"并记录日志"——此处返回，由调用方记录，避免日志中出现敏感值
        return fallback;
    }

    public String gitlabHost() {
        try {
            URI u = UriComponentsBuilder.fromHttpUrl(props.getGitlab().getBaseUrl())
                    .build().toUri();
            return u.getHost() == null ? "gitlab.local" : u.getHost();
        } catch (Exception e) {
            return "gitlab.local";
        }
    }
}
