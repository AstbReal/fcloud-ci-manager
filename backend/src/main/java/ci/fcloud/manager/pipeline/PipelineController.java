package ci.fcloud.manager.pipeline;

import ci.fcloud.manager.audit.AccessLogService;
import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.file.FileVersion;
import ci.fcloud.manager.token.ApiToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * §4 流水线 API（机器侧，只读）。
 *
 * 三个接口：
 *   GET  /api/v1/pipeline/config                  dotenv
 *   POST /api/v1/pipeline/artifacts               批量 tar.gz
 *   GET  /api/v1/pipeline/files/{moduleId}/{fileId} 单文件
 *
 * 鉴权与限流在 PipelineAuthFilter 中完成。
 * 严禁把任何变量值写入日志。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;
    private final AccessLogService accessLogService;
    private final ObjectMapper objectMapper;

    /**
     * §4.2 拉取变量（dotenv）。
     *
     * 响应 Content-Type: text/plain; charset=utf-8
     */
    @GetMapping(value = "/config", produces = "text/plain;charset=UTF-8")
    public void config(@RequestParam(required = false) String application,
                       @RequestParam(required = false) String environment,
                       @RequestParam(required = false) String modules,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        ApiToken token = tokenOf(request);
        try {
            if (environment == null || environment.isBlank()) {
                throw BizException.badRequest("environment 参数必填");
            }
            String text = pipelineService.buildConfig(token, application, environment, modules);
            response.setStatus(200);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(text);
            response.getWriter().flush();
            record(request, token, request.getRequestURI(), 200);
        } catch (BizException e) {
            writeError(response, e);
            record(request, token, request.getRequestURI(), statusOf(e));
        }
    }

    /**
     * §4.3 批量下载文件（tar.gz 流式）。
     *
     * Body: { "environment": "prod", "modules": ["db-config"], "files": ["schema.sql"] }
     * 目录结构：{moduleCode}/{fileKey}
     */
    @PostMapping("/artifacts")
    public void artifacts(@RequestBody(required = false) ArtifactsBody body,
                          HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        ApiToken token = tokenOf(request);
        try {
            if (body == null || body.getEnvironment() == null || body.getEnvironment().isBlank()) {
                throw BizException.badRequest("environment 必填");
            }
            if (body.getModules() == null || body.getModules().isEmpty()) {
                throw BizException.badRequest("modules 必填");
            }
            List<PipelineService.Artifact> artifacts = pipelineService.collectArtifacts(
                    token, body.getApplication(), body.getEnvironment(),
                    body.getModules(), body.getFiles());
            if (artifacts.isEmpty()) {
                throw new BizException(40400, "未找到匹配的文件");
            }

            response.setStatus(200);
            response.setContentType("application/gzip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"artifacts.tar.gz\"");

            try (OutputStream os = response.getOutputStream();
                 GzipCompressorOutputStream gz = new GzipCompressorOutputStream(os);
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(gz)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                for (PipelineService.Artifact a : artifacts) {
                    if (!Files.exists(a.path())) {
                        continue;
                    }
                    String entryName = a.moduleCode() + "/" + a.fileKey();
                    TarArchiveEntry entry = new TarArchiveEntry(a.path().toFile(), entryName);
                    tar.putArchiveEntry(entry);
                    Files.copy(a.path(), tar);
                    tar.closeArchiveEntry();
                }
                tar.finish();
            }
            record(request, token, request.getRequestURI(), 200);
        } catch (BizException e) {
            writeError(response, e);
            record(request, token, request.getRequestURI(), statusOf(e));
        }
    }

    /**
     * §4.4 单文件下载。
     *
     * version 省略 = 最新版本；响应 application/octet-stream，
     * Content-Disposition 带原始文件名。
     */
    @GetMapping("/files/{moduleId}/{fileId}")
    public void singleFile(@PathVariable String moduleId,
                           @PathVariable String fileId,
                           @RequestParam(required = false) Integer version,
                           HttpServletRequest request,
                           HttpServletResponse response) throws IOException {
        ApiToken token = tokenOf(request);
        try {
            FileVersion v = pipelineService.resolveSingleFile(
                    token, moduleId, fileId, version);
            java.nio.file.Path path = pipelineService.pathOf(v);
            if (!Files.exists(path)) {
                throw new BizException(40400, "文件不存在");
            }
            String name = v.getOriginalName() == null
                    ? String.valueOf(v.getId()) : v.getOriginalName();
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)
                    .replace("+", "%20");

            response.setStatus(200);
            response.setContentType(v.getMimeType() == null
                    ? "application/octet-stream" : v.getMimeType());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + encoded);
            if (v.getSize() != null) {
                response.setContentLengthLong(v.getSize());
            }
            Files.copy(path, response.getOutputStream());
            response.getOutputStream().flush();
            record(request, token, request.getRequestURI(), 200);
        } catch (BizException e) {
            writeError(response, e);
            record(request, token, request.getRequestURI(), statusOf(e));
        }
    }

    // ---------------- 辅助 ----------------

    private ApiToken tokenOf(HttpServletRequest request) {
        Object t = request.getAttribute(PipelineAuthFilter.ATTR_TOKEN);
        if (t instanceof ApiToken at) {
            return at;
        }
        throw new BizException(40100, "未鉴权");
    }

    private int statusOf(BizException e) {
        return switch (e.getCode()) {
            case 40100 -> 401;
            case 40300 -> 403;
            case 40400 -> 404;
            case 42900 -> 429;
            default -> 400;
        };
    }

    private void writeError(HttpServletResponse response, BizException e) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(statusOf(e));
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("error", e.getMessage()));
    }

    /**
     * §8 访问日志：只记 token_id/application_id/endpoint/method/status/duration/ip。
     * 不记任何变量值或查询参数中的值内容。
     */
    private void record(HttpServletRequest request, ApiToken token,
                        String endpoint, int status) {
        Object started = request.getAttribute(PipelineAuthFilter.ATTR_START_MS);
        long duration = started instanceof Long s ? System.currentTimeMillis() - s : 0L;
        // 全局 Token 不再绑定应用，访问日志的 application_id 留空
        accessLogService.record(token.getId(), null,
                endpoint, request.getMethod(), status, duration, clientIp(request));
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @Data
    public static class ArtifactsBody {
        private String application;
        private String environment;
        private List<String> modules;
        private List<String> files;
    }
}
