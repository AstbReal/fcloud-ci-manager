package ci.fcloud.manager.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * §7 审计日志清理：保留 7 天，每日 03:00 执行
 * DELETE WHERE created_at < now() - interval '7 days'（不用 Redis TTL）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditCleanupJob {

    private final AuditService auditService;
    private final ci.fcloud.manager.config.FcloudProperties props;

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanOldAuditLogs() {
        long retainDays = props.getAudit().getRetainDays();
        Instant threshold = Instant.now().minus(retainDays, ChronoUnit.DAYS);
        int removed = auditService.getBaseMapper().delete(
                new LambdaQueryWrapper<AuditLog>()
                        .lt(AuditLog::getCreatedAt, threshold));
        if (removed > 0) {
            log.info("audit cleanup: removed {} rows older than {} days",
                    removed, retainDays);
        }
    }
}
