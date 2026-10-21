package ci.fcloud.hub.audit;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * §7 @Audited 切面：方法成功执行后异步写审计。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditService auditService;

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        Object result = pjp.proceed();
        try {
            Object targetId = extractTargetId(pjp);
            auditService.log(audited.action(), audited.targetType(),
                    targetId == null ? null : String.valueOf(targetId),
                    audited.critical(), null);
        } catch (Exception e) {
            // 审计异常绝不影响业务
        }
        return result;
    }

    /**
     * 约定：方法参数中第一个 Long/String 作为 targetId。
     */
    private Object extractTargetId(ProceedingJoinPoint pjp) {
        for (Object arg : pjp.getArgs()) {
            if (arg instanceof Long l) {
                return l;
            }
            if (arg instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
