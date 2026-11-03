package ci.fcloud.manager.backup;

import ci.fcloud.manager.audit.AuditService;
import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.common.SecurityUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * §5.9 备份记录 / 立即备份 / 下载 / 恢复（仅系统管理员）。
 */
@RestController
@RequestMapping("/api/console/admin/backups")
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;
    private final AuditService auditService;

    @GetMapping
    public ApiResponse<List<BackupRecord>> list() {
        return ApiResponse.ok(backupService.listAll());
    }

    /**
     * §5.9 立即备份：返回 RUNNING 记录，打包在后台进行，前端轮询列表看状态。
     */
    @PostMapping
    public ApiResponse<BackupRecord> create() {
        BackupRecord r = backupService.createAsync("MANUAL", SecurityUtils.currentUserId());
        auditService.log("BACKUP_CREATE", "BACKUP", String.valueOf(r.getId()), false,
                auditService.detailJson("fileName", r.getFileName(), "type", r.getType()));
        return ApiResponse.ok(r);
    }

    /**
     * §5.9 下载备份包。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        BackupRecord r = backupService.requireById(id);
        if (!"SUCCESS".equals(r.getStatus())) {
            throw new BizException(40900, "备份" + ("RUNNING".equals(r.getStatus())
                    ? "还在进行中" : "已失败") + "，不能下载");
        }
        java.nio.file.Path p = Paths.get(r.getFilePath());
        if (!Files.exists(p)) {
            throw new BizException(40400, "备份文件已不存在");
        }
        auditService.log("BACKUP_DOWNLOAD", "BACKUP", String.valueOf(id), false,
                auditService.detailJson("fileName", r.getFileName()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + r.getFileName() + "\"")
                .contentType(MediaType.parseMediaType("application/gzip"))
                .body(new FileSystemResource(p));
    }

    /**
     * §5.9 恢复：multipart file + strategy(ABORT|MERGE|OVERWRITE)。
     * 上传完成后立即返回 RUNNING 任务，解包导入在后台进行，前端轮询 /restores。
     * 恢复属高危操作（§7）。
     */
    @PostMapping("/restore")
    public ApiResponse<RestoreJob> restore(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "strategy", defaultValue = "ABORT") String strategy) {
        if (file == null || file.isEmpty()) {
            throw new BizException(40000, "请上传备份包");
        }
        RestoreJob job = backupService.submitRestore(
                file, strategy, SecurityUtils.currentUserId());
        auditService.log("BACKUP_RESTORE", "RESTORE", String.valueOf(job.getId()), true,
                auditService.detailJson("strategy", job.getStrategy(),
                        "fileName", job.getFileName(), "size", job.getSize(),
                        "mode", "ASYNC"));
        return ApiResponse.ok(job);
    }

    /** 最近的恢复任务（含进行中与失败原因） */
    @GetMapping("/restores")
    public ApiResponse<List<RestoreJob>> restores() {
        return ApiResponse.ok(backupService.listRestores());
    }

    // ---- 制品库（Gitee 仓库） ----

    /** 制品库上可用的备份列表。文件名带时间戳，直接从中解析生成时间，不再逐文件查提交历史。 */
    @GetMapping("/remote")
    public ApiResponse<List<RemoteBackupVO>> remote() {
        List<RemoteBackupVO> out = new java.util.ArrayList<>();
        for (var f : backupService.listRemote()) {
            RemoteBackupVO vo = new RemoteBackupVO();
            vo.setName(f.name());
            // 通道是 Release 附件，没有目录层级；path 就是附件名，回传给恢复接口
            vo.setPath(f.name());
            vo.setSize(f.size());
            vo.setCreatedAt(parseTimestamp(f.name()));
            out.add(vo);
        }
        return ApiResponse.ok(out);
    }

    /** 上传失败后手动重试。已上传的再点一次会覆盖同名文件。 */
    @PostMapping("/{id}/upload")
    public ApiResponse<BackupRecord> upload(@PathVariable Long id) {
        BackupRecord r = backupService.requireById(id);
        if (!"SUCCESS".equals(r.getStatus())) {
            throw new BizException(40900, "只有本地成功的备份才能上传");
        }
        BackupRecord after = backupService.uploadToRemote(r);
        auditService.log("BACKUP_UPLOAD", "BACKUP", String.valueOf(id), false,
                auditService.detailJson("fileName", r.getFileName(),
                        "remoteStatus", after.getRemoteStatus()));
        return ApiResponse.ok(after);
    }

    /** 从制品库的备份恢复（下载到临时文件后走与上传恢复相同的后台任务）。 */
    @PostMapping("/restore-remote")
    public ApiResponse<RestoreJob> restoreRemote(@RequestBody RemoteRestoreBody body) {
        RestoreJob job = backupService.submitRestoreFromRemote(
                body.getRemotePath(), body.getStrategy(), SecurityUtils.currentUserId());
        auditService.log("BACKUP_RESTORE", "RESTORE", String.valueOf(job.getId()), true,
                auditService.detailJson("strategy", job.getStrategy(),
                        "fileName", job.getFileName(), "source", "GITEE"));
        return ApiResponse.ok(job);
    }

    /** 文件名形如 fcloud-backup-20261022-005907.tar.gz，解析失败返回 null。 */
    private static java.time.Instant parseTimestamp(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{8})-(\\d{6})").matcher(name == null ? "" : name);
        if (!m.find()) {
            return null;
        }
        try {
            return java.time.LocalDateTime
                    .parse(m.group(1) + m.group(2),
                            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    @Data
    public static class RemoteBackupVO {
        private String name;
        private String path;
        private long size;
        private String sha;
        private java.time.Instant createdAt;
    }

    @Data
    public static class RemoteRestoreBody {
        private String remotePath;
        private String strategy;
    }

    @Data
    public static class RestoreBody {
        private String strategy;
    }
}
