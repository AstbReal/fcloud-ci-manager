package ci.fcloud.hub.backup;

import ci.fcloud.hub.audit.AuditService;
import ci.fcloud.hub.common.ApiResponse;
import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.common.SecurityUtils;
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

    @Data
    public static class RestoreBody {
        private String strategy;
    }
}
