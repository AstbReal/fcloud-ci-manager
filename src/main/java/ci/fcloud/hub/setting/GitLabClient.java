package ci.fcloud.hub.setting;

import ci.fcloud.hub.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 GitLab REST 客户端：只用到「校验身份」和「列项目」两个接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitLabClient {

    private final GitLabSettingService settings;

    public record Project(long id, String name, String pathWithNamespace, String webUrl,
                          String description, String defaultBranch, String lastActivityAt) {
    }

    public Map<String, Object> me() {
        JsonNode n = call("/api/v4/user");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", n.path("username").asText(""));
        out.put("name", n.path("name").asText(""));
        out.put("avatarUrl", n.path("avatar_url").asText(null));
        return out;
    }

    public List<Project> projects(String search) {
        StringBuilder path = new StringBuilder("/api/v4/projects")
                .append("?membership=true&simple=true&per_page=50")
                .append("&order_by=last_activity_at&sort=desc");
        if (search != null && !search.isBlank()) {
            path.append("&search=").append(urlEncode(search.trim()));
        }
        JsonNode arr = call(path.toString());
        List<Project> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode p : arr) {
                out.add(new Project(
                        p.path("id").asLong(),
                        p.path("name").asText(""),
                        p.path("path_with_namespace").asText(""),
                        p.path("web_url").asText(""),
                        p.path("description").asText(""),
                        p.path("default_branch").asText(""),
                        p.path("last_activity_at").asText("")));
            }
        }
        return out;
    }

    private JsonNode call(String path) {
        GitLabSettingService.Config cfg = settings.load();
        if (cfg.token() == null || cfg.token().isBlank()) {
            throw BizException.badRequest("还没有配置 GitLab Token");
        }
        String url = cfg.baseUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.set("PRIVATE-TOKEN", cfg.token());
        try {
            ResponseEntity<JsonNode> res = restTemplate().exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            JsonNode body = res.getBody();
            if (body == null) {
                throw new BizException(50200, "GitLab 返回空响应");
            }
            return body;
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            int status = e.getRawStatusCode();
            if (status == 401 || status == 403) {
                throw new BizException(40000, "GitLab Token 无效或没有权限（" + status + "）");
            }
            if (status == 404) {
                throw new BizException(40000, "GitLab 地址不对：接口返回 404，请检查 Base URL");
            }
            throw new BizException(50200, "GitLab 接口错误：" + status);
        } catch (RestClientException e) {
            log.warn("gitlab call failed: {} {}", url, e.getMessage());
            throw new BizException(50200, "无法连接 GitLab，请检查地址与网络");
        }
    }

    private RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(10000);
        return new RestTemplate(f);
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
