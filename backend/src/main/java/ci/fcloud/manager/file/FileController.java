package ci.fcloud.manager.file;

import ci.fcloud.manager.audit.AuditService;
import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.module.ModuleService;
import ci.fcloud.manager.module.PermissionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * §5.6 文件（上传即新版本 / 版本列表 / 下载 / 删除 / 改备注）
 */
@RestController
@RequestMapping("/api/console")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final ModuleService moduleService;
    private final PermissionService permissionService;
    private final AuditService auditService;

    @GetMapping("/modules/{moduleId}/files")
    public ApiResponse<List<FileVO>> list(@PathVariable Long moduleId,
                                          @RequestParam(required = false) Long environmentId) {
        permissionService.requireCanView(moduleId);
        List<ModuleFile> files = fileService.listByModule(moduleId, environmentId);
        return ApiResponse.ok(files.stream().map(f -> {
            FileVO vo = new FileVO();
            vo.setId(f.getId());
            vo.setModuleId(f.getModuleId());
            vo.setEnvironmentId(f.getEnvironmentId());
            vo.setFileKey(f.getFileKey());
            vo.setRemark(f.getRemark());
            vo.setCurrentVersion(f.getCurrentVersion());
            vo.setOrigin(f.getOrigin());
            vo.setCreatedAt(f.getCreatedAt());
            vo.setUpdatedAt(f.getUpdatedAt());
            return vo;
        }).toList());
    }

    /**
     * §5.6 multipart：file, fileKey, environmentId, remark —— 上传即新版本
     */
    @PostMapping("/modules/{moduleId}/files")
    public ApiResponse<FileVO> upload(@PathVariable Long moduleId,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam("fileKey") String fileKey,
                                      @RequestParam("environmentId") Long environmentId,
                                      @RequestParam(value = "remark", required = false) String remark) {
        ModuleFile saved = fileService.upload(moduleId, environmentId, fileKey, file, remark);
        FileVO vo = new FileVO();
        vo.setId(saved.getId());
        vo.setModuleId(saved.getModuleId());
        vo.setEnvironmentId(saved.getEnvironmentId());
        vo.setFileKey(saved.getFileKey());
        vo.setRemark(saved.getRemark());
        vo.setCurrentVersion(saved.getCurrentVersion());
        vo.setOrigin(saved.getOrigin());
        return ApiResponse.ok(vo);
    }

    @GetMapping("/files/{fileId}/versions")
    public ApiResponse<List<VersionVO>> versions(@PathVariable Long fileId) {
        ModuleFile f = fileService.requireById(fileId);
        permissionService.requireCanView(f.getModuleId());
        return ApiResponse.ok(fileService.versionsOf(fileId).stream().map(v -> {
            VersionVO vo = new VersionVO();
            vo.setId(v.getId());
            vo.setVersion(v.getVersion());
            vo.setOriginalName(v.getOriginalName());
            vo.setMimeType(v.getMimeType());
            vo.setSize(v.getSize());
            vo.setUploadedBy(v.getUploadedBy());
            vo.setCreatedAt(v.getCreatedAt());
            return vo;
        }).toList());
    }

    /**
     * §5.6 下载：version 省略 = 最新版本。
     */
    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long fileId,
                                             @RequestParam(required = false) Integer version)
            throws Exception {
        ModuleFile f = fileService.requireById(fileId);
        permissionService.requireCanView(f.getModuleId());
        FileVersion v = fileService.resolveVersion(fileId, version);
        java.nio.file.Path path = fileService.resolvePath(v);
        if (!java.nio.file.Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        InputStream in = new FileInputStream(path.toFile());
        InputStreamResource resource = new InputStreamResource(in);
        String filename = URLEncoder.encode(
                v.getOriginalName() == null ? f.getFileKey() : v.getOriginalName(),
                StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        v.getMimeType() == null ? "application/octet-stream" : v.getMimeType()))
                .contentLength(v.getSize() == null ? 0 : v.getSize())
                .body(resource);
    }

    /**
     * 敏感文件（.key/.pem/.env 等）预览前的一次显式动作：记一条审计。
     */
    @PostMapping("/files/{fileId}/reveal")
    public ApiResponse<Void> revealPreview(@PathVariable Long fileId,
                                           @RequestParam(required = false) Integer version) {
        ModuleFile f = fileService.requireById(fileId);
        permissionService.requireCanView(f.getModuleId());
        auditService.log("FILE_PREVIEW", "FILE", String.valueOf(fileId), true,
                auditService.detailJson(
                        "moduleId", f.getModuleId(),
                        "fileKey", f.getFileKey(),
                        "version", version));
        return ApiResponse.ok();
    }

    @DeleteMapping("/files/{fileId}")
    public ApiResponse<Void> delete(@PathVariable Long fileId) {
        fileService.delete(fileId);
        return ApiResponse.ok();
    }

    @PutMapping("/files/{fileId}")
    public ApiResponse<FileVO> updateRemark(@PathVariable Long fileId,
                                            @RequestBody RemarkBody body) {
        ModuleFile f = fileService.updateRemark(fileId, body.getRemark());
        FileVO vo = new FileVO();
        vo.setId(f.getId());
        vo.setFileKey(f.getFileKey());
        vo.setRemark(f.getRemark());
        vo.setCurrentVersion(f.getCurrentVersion());
        return ApiResponse.ok(vo);
    }

    @Data
    public static class RemarkBody {
        private String remark;
    }

    @Data
    public static class FileVO {
        private Long id;
        private Long moduleId;
        private Long environmentId;
        private String fileKey;
        private String remark;
        private Integer currentVersion;
        private String origin;
        private java.time.Instant createdAt;
        private java.time.Instant updatedAt;
    }

    @Data
    public static class VersionVO {
        private Long id;
        private Integer version;
        private String originalName;
        private String mimeType;
        private Long size;
        private Long uploadedBy;
        private java.time.Instant createdAt;
    }
}
