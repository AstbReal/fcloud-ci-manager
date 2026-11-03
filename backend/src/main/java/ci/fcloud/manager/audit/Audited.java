package ci.fcloud.manager.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * §7 审计注解：由 Spring AOP 切面自动记录。
 * 删除类操作建议标 critical = true。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** 动作名，如 ENV_CREATE */
    String action();

    /** 目标类型，如 ENVIRONMENT / MODULE / VARIABLE */
    String targetType() default "";

    /** 是否高危 */
    boolean critical() default false;
}
