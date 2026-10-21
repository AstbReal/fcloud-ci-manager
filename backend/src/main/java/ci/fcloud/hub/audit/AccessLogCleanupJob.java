package ci.fcloud.hub.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * §8 访问日志清理：保留 30 天，每日 03:30 执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessLogCleanupJob {

    private final AccessLogService accessLogService;
    private final ci.fcloud.hub.config.FcloudProperties props;

    @Scheduled(cron = "0 30 3 * * *")
    public void cleanOldAccessLogs() {
        long retainDays = props.getAccessLog().getRetainDays();
        Instant threshold = Instant.now().minus(retainDays, ChronoUnit.DAYS);
        int removed = accessLogService.getBaseMapper().delete(
                new LambdaQueryWrapper<AccessLog>()
                        .lt(AccessLog::getCreatedAt, threshold));
        if (removed > 0) {
            log.info("access log cleanup: removed {} rows older than {} days",
                    removed, retainDays);
        }
    }
}
