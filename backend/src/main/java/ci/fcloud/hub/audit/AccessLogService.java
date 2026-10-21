package ci.fcloud.hub.audit;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * §8 流水线访问日志：异步写入，保留 30 天。
 * 流水线 API 调用**不进审计**，只进访问日志（§7 表末行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessLogService extends ServiceImpl<AccessLogMapper, AccessLog> {

    @Async
    public void record(Long tokenId, Long applicationId, String endpoint,
                       String method, int status, long durationMs, String ip) {
        try {
            AccessLog a = new AccessLog();
            a.setTokenId(tokenId);
            a.setApplicationId(applicationId);
            a.setEndpoint(endpoint);
            a.setMethod(method);
            a.setStatus(status);
            a.setDurationMs(durationMs);
            a.setIp(ip);
            a.setCreatedAt(Instant.now());
            save(a);
        } catch (Exception e) {
            log.warn("access log write failed: {}", e.getMessage());
        }
    }
}
