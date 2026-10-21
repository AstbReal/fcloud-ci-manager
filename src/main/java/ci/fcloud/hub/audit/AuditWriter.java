package ci.fcloud.hub.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 审计异步落库。
 *
 * 单独抽成 Bean 的原因：{@code @Async} 走的是代理，同类内部调用不会异步；
 * 更重要的是——异步线程里拿不到 SecurityContext 和 HttpServletRequest，
 * 所以用户信息、IP 必须在请求线程里组装好，这里只负责写。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditWriter {

    private final AuditLogMapper mapper;

    @Async
    public void write(AuditLog entry) {
        try {
            mapper.insert(entry);
        } catch (Exception e) {
            // 审计失败不能影响主流程，但必须留下痕迹
            log.error("audit write failed: action={} err={}", entry.getAction(), e.getMessage());
        }
    }
}
