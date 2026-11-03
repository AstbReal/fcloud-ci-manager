package ci.fcloud.manager.module;

import ci.fcloud.manager.common.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * §4.2 dotenv 序列化 / 解析。
 *
 * 规则：
 * - 每个模块前输出 `# module: {code}` 注释行；变量按 sort_order
 * - 值转义：含空格 / `#` / 单引号 / 换行 / `$` 时用单引号包裹，内部单引号转 `'\''`
 * - 同名 key 后出现的模块覆盖先出现的（last-wins）
 */
@Component
@RequiredArgsConstructor
public class DotenvCodec {

    /**
     * 生成 dotenv 文本。modules 已按请求参数顺序排列，每个模块内按 sort_order。
     *
     * 同名 key 后出现的模块覆盖先出现的（last-wins）：dotenv 语义本身就是
     * "后赋值覆盖前赋值"，因此这里按顺序直接输出即可，无需额外去重；
     * 分组注释 `# module:` 让覆盖关系在输出中可见（§4.2）。
     *
     * @param valuesAlreadyResolved true 表示 entries 里的 value 已由调用方按权限
     *                             处理完毕（明文或 __MASKED__），直接输出；
     *                             false 表示加密值一律输出 __MASKED__
     */
    public String render(List<ModuleEntries> modules, boolean valuesAlreadyResolved,
                         CryptoService crypto) {
        StringBuilder sb = new StringBuilder();
        for (ModuleEntries m : modules) {
            sb.append("# module: ").append(m.moduleCode()).append('\n');
            for (Entry e : m.entries()) {
                String value = e.value();
                if (e.encrypted() && !valuesAlreadyResolved) {
                    value = "__MASKED__";
                }
                sb.append(e.key()).append('=').append(escape(value)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * §10.5 粘贴导入：解析 dotenv 文本。
     * 类型推断：key 以 PASSWORD|SECRET|TOKEN|KEY 结尾默认加密。
     */
    public List<Parsed> parse(String text) {
        List<Parsed> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (key.isEmpty()) {
                continue;
            }
            boolean quoted = value.length() >= 2 && value.startsWith("'") && value.endsWith("'");
            if (quoted) {
                String inner = value.substring(1, value.length() - 1);
                value = inner.replace("'\\''", "'");
            } else if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            out.add(new Parsed(key, value, inferEncrypted(key)));
        }
        return out;
    }

    /**
     * §10.5 类型推断：PASSWORD|SECRET|TOKEN|KEY 结尾默认加密。
     */
    public boolean inferEncrypted(String key) {
        String k = key.toUpperCase();
        return k.endsWith("PASSWORD") || k.endsWith("SECRET")
                || k.endsWith("TOKEN") || k.endsWith("KEY");
    }

    /**
     * §4.2 值转义：含空格 / `#` / 单引号 / 换行 / `$` 时用单引号包裹，内部单引号转 `'\''`
     */
    public String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuote = value.isEmpty()
                || value.chars().anyMatch(c -> c == ' ' || c == '#' || c == '\''
                || c == '\n' || c == '\r' || c == '$');
        if (!needQuote) {
            return value;
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public record Entry(String key, String value, boolean encrypted) {
    }

    public record ModuleEntries(String moduleCode, List<Entry> entries) {
    }

    public record Parsed(String key, String value, boolean encrypted) {
    }
}
