package ci.fcloud.manager.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具 inputSchema 的构造与参数读取工具。
 *
 * <p>schema 用 JSON 文本块写，比层层 Map.of 可读得多，
 * 解析一次后缓存即可。</p>
 */
public final class Schemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Schemas() {
    }

    public static Map<String, Object> of(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("工具 schema 不是合法 JSON", e);
        }
    }

    /** 按插入顺序构造结果 Map，允许 null 值（Map.of 不允许）。 */
    public static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static String requireString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: " + key);
        }
        return String.valueOf(v).trim();
    }

    public static String optString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    public static List<String> optStringList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim)
                    .filter(s -> !s.isEmpty()).toList();
        }
        // 容忍 "a,b,c" 这种简写
        return java.util.Arrays.stream(String.valueOf(v).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
