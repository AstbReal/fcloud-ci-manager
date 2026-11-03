package ci.fcloud.manager.audit;

import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.common.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * §5.9 / §7 审计查询（仅系统管理员，路径前缀 /api/console/admin）。
 */
@RestController
@RequestMapping("/api/console/admin/audit-logs")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    public ApiResponse<PageResult<AuditVO>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Boolean critical,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {

        LambdaQueryWrapper<AuditLog> w = new LambdaQueryWrapper<>();
        if (action != null && !action.isBlank()) {
            w.eq(AuditLog::getAction, action);
        }
        if (critical != null) {
            w.eq(AuditLog::getIsCritical, critical);
        }
        if (userId != null) {
            w.eq(AuditLog::getUserId, userId);
        }
        if (from != null) {
            w.ge(AuditLog::getCreatedAt, toInstant(from));
        }
        if (to != null) {
            w.le(AuditLog::getCreatedAt, toInstant(to));
        }
        w.orderByDesc(AuditLog::getCreatedAt);

        Page<AuditLog> p = auditService.page(new Page<>(page, size), w);
        return ApiResponse.ok(PageResult.of(
                p.getRecords().stream().map(AuditVO::of).toList(),
                p.getTotal(), page, size));
    }

    private Instant toInstant(LocalDateTime t) {
        return t.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }

    @Data
    public static class AuditVO {
        private Long id;
        private Long userId;
        private String userEmail;
        private String action;
        private String targetType;
        private String targetId;
        private String detail;
        private Boolean isCritical;
        private String ip;
        private Instant createdAt;

        static AuditVO of(AuditLog a) {
            AuditVO v = new AuditVO();
            v.setId(a.getId());
            v.setUserId(a.getUserId());
            v.setUserEmail(a.getUserEmail());
            v.setAction(a.getAction());
            v.setTargetType(a.getTargetType());
            v.setTargetId(a.getTargetId());
            v.setDetail(a.getDetail());
            v.setIsCritical(a.getIsCritical());
            v.setIp(a.getIp());
            v.setCreatedAt(a.getCreatedAt());
            return v;
        }
    }
}
