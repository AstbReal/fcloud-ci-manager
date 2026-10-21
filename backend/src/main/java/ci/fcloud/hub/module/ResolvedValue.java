package ci.fcloud.hub.module;

import lombok.Builder;
import lombok.Data;

/**
 * §2.2 变量解析结果。
 *
 * @param value       解析出的明文值（加密变量已解密）
 * @param source      命中来源，用于 UI 展示"继承自哪个环境/全局值/应用级覆盖"
 * @param masked      true 表示当前用户无权查看明文，value 为脱敏占位符
 * @param found       false 表示整条优先级链都未命中（该 key 不输出）
 */
@Data
@Builder
public class ResolvedValue {

    public enum Source {
        /** override(V, E, A) 应用级覆盖 · 当前环境 */
        APP_OVERRIDE,
        /** override(V, E, null) 模块级覆盖 · 当前环境 */
        MODULE_OVERRIDE,
        /** 沿父链的应用级覆盖（某个祖先环境） */
        APP_OVERRIDE_ANCESTOR,
        /** 沿父链的模块级覆盖（某个祖先环境） */
        MODULE_OVERRIDE_ANCESTOR,
        /** V.global_value */
        GLOBAL
    }

    private final String value;
    private final Source source;
    private final Long sourceEnvironmentId;
    private final boolean masked;
    private final boolean found;

    public static ResolvedValue notFound() {
        return ResolvedValue.builder().found(false).build();
    }

    public boolean isOverride() {
        return source == Source.APP_OVERRIDE
                || source == Source.MODULE_OVERRIDE
                || source == Source.APP_OVERRIDE_ANCESTOR
                || source == Source.MODULE_OVERRIDE_ANCESTOR;
    }

    public boolean isInherited() {
        return source == Source.APP_OVERRIDE_ANCESTOR
                || source == Source.MODULE_OVERRIDE_ANCESTOR;
    }
}
