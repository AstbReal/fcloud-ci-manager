package ci.fcloud.manager.setting;

import ci.fcloud.manager.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 制品库客户端：JFrog Artifactory V7 兼容接口（内网的 Gitee 制品库走这套协议）。
 *
 * <pre>
 * 上传  PUT    {base}/artifactory/{repo}/{dir}/{name}   body = 文件二进制
 * 下载  GET    {base}/artifactory/{repo}/{dir}/{name}
 * 删除  DELETE {base}/artifactory/{repo}/{dir}/{name}
 * 清单  GET    {base}/api/storage/{repo}/{dir}?list&amp;deep=0&amp;listFolders=0&amp;mdTimestamps=1
 * 探活  GET    {base}/api/system/ping + {base}/api/storage/{repo}
 * </pre>
 *
 * <p>上传是流式（{@link FileSystemResource}），不把整个备份包读进内存；
 * Artifactory 本身对单文件没有 20/100MB 这类限制，所以上限放到 2GB 只为兜底。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArtifactRepoClient {

    /** 兜底上限：Artifactory 本身不限，这里防止误传超大文件把部署卡死 */
    public static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;

    private final ArtifactRepoSettingService settings;

    public record RemoteFile(String name, long size) {
    }

    /** 探活：ping + 读仓库信息，顺带报告备份目录下的文件数。 */
    public Map<String, Object> probe() {
        ArtifactRepoSettingService.Config cfg = requireUsable();
        String ping = getText("/api/system/ping", cfg);
        JsonNode repo = getJson("/api/storage/" + enc(cfg.repo()), cfg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repo", repo.path("repo").asText(cfg.repo()));
        out.put("packageType", repo.path("packageType").asText(""));
        out.put("dir", cfg.dir());
        out.put("ping", ping == null ? "OK" : ping.trim());
        out.put("fileCount", list(cfg).size());
        out.put("uploadLimitMb", MAX_UPLOAD_BYTES / 1024 / 1024);
        return out;
    }

    public List<RemoteFile> list() {
        return list(requireUsable());
    }

    private List<RemoteFile> list(ArtifactRepoSettingService.Config cfg) {
        String path = "/api/storage/" + enc(cfg.repo())
                + (cfg.dir().isEmpty() ? "" : "/" + encodePath(cfg.dir()))
                + "?list&deep=0&listFolders=0&mdTimestamps=1";
        JsonNode node;
        try {
            node = getJson(path, cfg);
        } catch (BizException e) {
            // 目录还没建过（首次上传前）是正常状态
            if (e.getCode() == 40400) {
                return List.of();
            }
            throw e;
        }
        List<RemoteFile> out = new ArrayList<>();
        JsonNode children = node.path("children");
        if (children.isArray()) {
            for (JsonNode c : children) {
                String uri = c.path("uri").asText("");
                if (uri.isEmpty()) {
                    continue;
                }
                out.add(new RemoteFile(
                        URLDecoder.decode(uri.replaceFirst("^/+", ""), StandardCharsets.UTF_8),
                        c.path("size").asLong(0)));
            }
        }
        return out;
    }

    /** 上传备份包。Artifactory 的同路径 PUT 是覆盖写，不需要先删。 */
    public void upload(String fileName, Path file) {
        ArtifactRepoSettingService.Config cfg = requireUsable();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new BizException(40000, "读取备份文件失败：" + e.getMessage());
        }
        if (size > MAX_UPLOAD_BYTES) {
            throw new BizException(40000, "备份包 " + human(size) + " 超过制品库上传上限 "
                    + human(MAX_UPLOAD_BYTES) + "，已跳过上传（本地备份不受影响）");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        auth(headers, cfg);

        String url = cfg.baseUrl() + "/artifactory/" + enc(cfg.repo()) + "/"
                + encodePath(artifactPath(cfg, fileName));
        try {
            restTemplate(10_000, 1_800_000).exchange(url, HttpMethod.PUT,
                    new HttpEntity<>(new FileSystemResource(file), headers), JsonNode.class);
        } catch (HttpStatusCodeException e) {
            int status = e.getRawStatusCode();
            if (status == 401) {
                throw new BizException(40000, "制品库认证失败（401），检查用户名/Token");
            }
            if (status == 403) {
                throw new BizException(40000, "没有该仓库的 deploy 权限（403）");
            }
            if (status == 404) {
                throw new BizException(40000, "仓库不存在（404）：" + cfg.repo());
            }
            if (status == 405) {
                throw new BizException(40000,
                        "该路径不接受 PUT（405），可能指向了虚拟仓库，请换成宿主仓库 key");
            }
            throw new BizException(50200, "上传到制品库失败：" + status);
        } catch (RestClientException e) {
            log.warn("artifact upload failed: {}", e.getMessage());
            throw new BizException(50200, "无法连接制品库，请检查地址与网络");
        }
    }

    /** 下载备份包到本地文件。 */
    public void download(String fileName, Path target) {
        ArtifactRepoSettingService.Config cfg = requireUsable();
        HttpHeaders headers = new HttpHeaders();
        auth(headers, cfg);
        String url = cfg.baseUrl() + "/artifactory/" + enc(cfg.repo()) + "/"
                + encodePath(artifactPath(cfg, fileName));
        try {
            restTemplate(10_000, 1_800_000).execute(url, HttpMethod.GET,
                    // execute() 只收 RequestCallback，鉴权头得在这里塞进去
                    request -> request.getHeaders().addAll(headers),
                    response -> {
                        try (InputStream in = response.getBody()) {
                            Files.copy(in, target,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        return null;
                    });
        } catch (HttpStatusCodeException e) {
            int status = e.getRawStatusCode();
            if (status == 404) {
                throw new BizException(40400, "制品库里找不到这个备份：" + fileName);
            }
            if (status == 401 || status == 403) {
                throw new BizException(40000, "制品库认证失败或没有读权限（" + status + "）");
            }
            throw new BizException(50200, "从制品库下载失败：" + status);
        } catch (RestClientException e) {
            log.warn("artifact download failed: {}", e.getMessage());
            throw new BizException(50200, "无法连接制品库，请检查地址与网络");
        }
    }

    /** 删除制品库上的备份（清理超出保留份数的旧包）。 */
    public void delete(String fileName) {
        ArtifactRepoSettingService.Config cfg = requireUsable();
        HttpHeaders headers = new HttpHeaders();
        auth(headers, cfg);
        String url = cfg.baseUrl() + "/artifactory/" + enc(cfg.repo()) + "/"
                + encodePath(artifactPath(cfg, fileName));
        try {
            restTemplate(5_000, 20_000).exchange(url, HttpMethod.DELETE,
                    new HttpEntity<>(headers), JsonNode.class);
        } catch (Exception e) {
            // 清理失败不阻塞主流程，下次上传还会再试
            log.warn("artifact delete {} failed: {}", fileName, e.getMessage());
        }
    }

    // ---------------- 内部 ----------------

    /** Artifactory 部署用的路径：dir/filename（dir 为空则只有文件名）。 */
    private static String artifactPath(ArtifactRepoSettingService.Config cfg, String fileName) {
        return cfg.dir() == null || cfg.dir().isEmpty() ? fileName : cfg.dir() + "/" + fileName;
    }

    /**
     * 鉴权：给了用户名走 Basic（用户名 + Token），否则走 Bearer。
     * Artifactory 两种都支持，内网代理通常只实现其中一种。
     */
    private static void auth(HttpHeaders headers, ArtifactRepoSettingService.Config cfg) {
        if (cfg.username() != null && !cfg.username().isBlank()) {
            headers.setBasicAuth(cfg.username(), cfg.token());
        } else {
            headers.setBearerAuth(cfg.token());
        }
    }

    private String getText(String path, ArtifactRepoSettingService.Config cfg) {
        HttpHeaders headers = new HttpHeaders();
        auth(headers, cfg);
        try {
            ResponseEntity<String> res = restTemplate(5_000, 15_000)
                    .exchange(cfg.baseUrl() + path, HttpMethod.GET,
                            new HttpEntity<>(headers), String.class);
            return res.getBody();
        } catch (HttpStatusCodeException e) {
            throw mapStatus(e.getRawStatusCode());
        } catch (RestClientException e) {
            log.warn("artifact ping failed: {}", e.getMessage());
            throw new BizException(50200, "无法连接制品库，请检查地址与网络");
        }
    }

    private JsonNode getJson(String path, ArtifactRepoSettingService.Config cfg) {
        HttpHeaders headers = new HttpHeaders();
        auth(headers, cfg);
        try {
            ResponseEntity<JsonNode> res = restTemplate(5_000, 20_000)
                    .exchange(cfg.baseUrl() + path, HttpMethod.GET,
                            new HttpEntity<>(headers), JsonNode.class);
            JsonNode body = res.getBody();
            if (body == null) {
                throw new BizException(50200, "制品库返回空响应");
            }
            return body;
        } catch (HttpStatusCodeException e) {
            throw mapStatus(e.getRawStatusCode());
        } catch (RestClientException e) {
            log.warn("artifact request failed: {}", e.getMessage());
            throw new BizException(50200, "无法连接制品库，请检查地址与网络");
        }
    }

    private BizException mapStatus(int status) {
        if (status == 401) {
            return new BizException(40000, "制品库认证失败（401），检查用户名/Token");
        }
        if (status == 403) {
            return new BizException(40000, "没有该仓库的读权限（403）");
        }
        if (status == 404) {
            return new BizException(40400, "制品库地址或仓库不存在（404）");
        }
        return new BizException(50200, "制品库接口错误：" + status);
    }

    private ArtifactRepoSettingService.Config requireUsable() {
        ArtifactRepoSettingService.Config cfg = settings.load();
        if (!cfg.enabled()) {
            throw BizException.badRequest("还没有开启制品库上传");
        }
        if (cfg.baseUrl() == null || cfg.baseUrl().isBlank()) {
            throw BizException.badRequest("还没有配置制品库地址");
        }
        if (cfg.repo() == null || cfg.repo().isBlank()) {
            throw BizException.badRequest("还没有配置制品库的仓库 Key");
        }
        if (cfg.token() == null || cfg.token().isBlank()) {
            throw BizException.badRequest("还没有配置制品库 Token");
        }
        return cfg;
    }

    private RestTemplate restTemplate(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return new RestTemplate(f);
    }

    public static String joinPath(String dir, String fileName) {
        return dir == null || dir.isEmpty() ? fileName : dir + "/" + fileName;
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(enc(part));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
