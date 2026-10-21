package ci.fcloud.hub.mcp.protocol;

import java.util.List;
import java.util.Map;

import ci.fcloud.hub.mcp.McpToolException;

/**
 * 极简 JSON Schema 校验：只做 required 与基本类型。
 *
 * <p>不引第三方校验器——工具入参结构都很浅，完整 draft-07 支持是过度设计。
 * 但 required 必须校验，否则模型漏参数时会拿到一句莫名的 NPE。</p>
 */
public final class SchemaValidator {

    private SchemaValidator() {
    }

    public static void validate(Map<String, Object> schema, Map<String, Object> args) {
        Object required = schema.get("required");
        if (required instanceof List<?> list) {
            for (Object r : list) {
                String key = String.valueOf(r);
                Object v = args.get(key);
                if (v == null || (v instanceof String s && s.isBlank())) {
                    throw new McpToolException("缺少必填参数: " + key);
                }
            }
        }
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object val = args.get(key);
                if (val == null || !(e.getValue() instanceof Map<?, ?> def)) {
                    continue;
                }
                Object type = def.get("type");
                if (type == null) {
                    continue;
                }
                if (!matches(String.valueOf(type), val)) {
                    throw new McpToolException("参数 " + key + " 类型应为 " + type);
                }
            }
        }
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof java.math.BigInteger;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true;
        };
    }
}
