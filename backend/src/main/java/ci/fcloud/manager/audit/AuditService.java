package ci.fcloud.manager.audit;

import ci.fcloud.manager.common.SecurityUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §7 审计日志。
 * - Spring AOP {@code @Audited} 注解 + 手动 {@link #log} 补充；异步写库
 * - 保留 7 天，每日 03:00 定时清理（见 AuditCleanupJob）
 * - 流水线 API 调用**不进审计**，进访问日志（§7 表末行）
 *
 * 注意：任何变量值都不得进入 detail。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService extends ServiceImpl<AuditLogMapper, AuditLog> {

    private final ObjectMapper objectMapper;
    private final AuditWriter auditWriter;

    /**
     * 记录一条审计。
     *
     * @param action     动作，如 LOGIN / ENV_CREATE / VARIABLE_REVEAL
     * @param targetType 目标类型，如 ENVIRONMENT / MODULE / VARIABLE
     * @param targetId   目标 id
     * @param critical   是否高危
     * @param detail     JSON 字符串；严禁包含变量值
     */
    public void log(String action, String targetType, String targetId,
                    boolean critical, String detail) {
        try {
            // 用户 / IP / 时间必须在请求线程里取，异步线程拿不到 SecurityContext 与 request
            AuditLog a = new AuditLog();
            a.setUserId(SecurityUtils.currentUserId());
            a.setUserEmail(SecurityUtils.currentUserEmail());
            a.setIp(currentIp());
            a.setAction(action);
            a.setTargetType(targetType);
            a.setTargetId(targetId == null ? null : String.valueOf(targetId));
            a.setDetail(detail);
            a.setIsCritical(critical);
            a.setCreatedAt(Instant.now());
            auditWriter.write(a);
        } catch (Exception e) {
            log.error("audit prepare failed: action={} err={}", action, e.getMessage());
        }
    }

    /**
     * 取客户端 IP：优先代理头，最后回落到 remoteAddr。
     * 非 HTTP 线程（定时任务）返回 null。
     */
    private String currentIp() {
        try {
            if (!(RequestContextHolder.getRequestAttributes()
                    instanceof ServletRequestAttributes attrs)) {
                return null;
            }
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
            String real = req.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return real.trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 便捷方法：用 Map 构造 detail JSON。
     */
    public String detailJson(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("detail kv must be pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }
}
