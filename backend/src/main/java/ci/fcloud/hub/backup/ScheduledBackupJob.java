package ci.fcloud.hub.backup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * §9.2 定时备份：每日 02:00，输出到 {backup.dir}，保留最近 N 份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledBackupJob {

    private final BackupService backupService;

    @Scheduled(cron = "0 0 2 * * *")
    public void dailyBackup() {
        try {
            BackupRecord r = backupService.create("SCHEDULED", null);
            log.info("scheduled backup created: {} ({} bytes)",
                    r.getFileName(), r.getSize());
        } catch (Exception e) {
            log.error("scheduled backup failed: {}", e.getMessage());
        }
    }
}
