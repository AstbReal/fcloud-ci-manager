package ci.fcloud.manager.mcp.tool.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ci.fcloud.manager.mcp.McpContext;
import ci.fcloud.manager.mcp.McpSettingService;
import ci.fcloud.manager.mcp.McpToolException;
import ci.fcloud.manager.mcp.tool.AbstractReadTool;
import ci.fcloud.manager.mcp.tool.McpTool;
import ci.fcloud.manager.mcp.tool.Schemas;
import ci.fcloud.manager.mcp.tool.ToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 拉取解析后的配置（dotenv）。
 *
 * <p>与流水线 API 返回逐字节一致——这是刻意设计：模型给出的答案
 * 必须和 CI 实际拿到的配置同源，否则排查结论会跑偏。</p>
 */
@Component
public class GetConfigTool extends AbstractReadTool implements McpTool {

    @Autowired
    private McpSettingService mcpSettings;

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "application": {"type": "string", "description": "应用 code"},
                "environment": {"type": "string", "description": "环境 code"},
                "modules": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "模块 code 列表，按顺序输出；同名 key 后者覆盖前者"
                },
                "keys": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "只看这些 key，可省略；用于收窄返回体积"
                },
                "revealSecrets": {
                  "type": "boolean",
                  "description": "是否输出加密变量明文，默认 false（脱敏为 ••••••）；仅排障确需时传 true"
                }
              },
              "required": ["application", "environment", "modules"]
            }
            """);

    @Override
    public String name() {
        return "get_config";
    }

    @Override
    public String description() {
        return "按 应用 + 环境 + 模块 生成最终生效的 dotenv 配置（含变量解析与覆盖）。"
                + "要「这个应用在 prod 的完整配置」时用这个；只想看某个 key 的来源用 explain_config_value。"
                + "加密变量默认脱敏为 ••••••；确需明文时显式传 revealSecrets=true。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, McpContext ctx) {
        String app = requireStr(args, "application");
        String env = requireStr(args, "environment");
        List<String> modules = optList(args, "modules");
        if (modules.isEmpty()) {
            throw new McpToolException("modules 必填：请先用 list_modules 确认模块 code");
        }
        // 明文需要「客户端显式请求」+「服务端开关打开」两个条件同时满足
        boolean reveal = Boolean.TRUE.equals(args.get("revealSecrets"))
                && mcpSettings.revealEnabled();

        String dotenv = pipelineService.buildConfig(app, env, String.join(",", modules), reveal);
        List<String> keys = optList(args, "keys");
        if (!keys.isEmpty()) {
            dotenv = filterKeys(dotenv, keys);
            if (dotenv.isBlank()) {
                throw new McpToolException("指定的 key 在这些模块里都不存在，先用 get_variable_matrix 查看现有 key");
            }
        }

        return ToolResult.ok(dotenv, Schemas.row(
                "application", app,
                "environment", env,
                "modules", modules,
                "filtered", !keys.isEmpty(),
                "secretsRevealed", reveal,
                "bytes", dotenv.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
    }

    private String filterKeys(String dotenv, List<String> keys) {
        Set<String> keep = new HashSet<>(keys);
        StringBuilder sb = new StringBuilder();
        for (String line : dotenv.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) {
                sb.append(line).append('\n');
                continue;
            }
            int eq = line.indexOf('=');
            String key = eq > 0 ? line.substring(0, eq) : line;
            if (keep.contains(key)) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
