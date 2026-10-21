package ci.fcloud.hub.mcp.tool.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ci.fcloud.hub.env.Environment;
import ci.fcloud.hub.mcp.McpContext;
import ci.fcloud.hub.mcp.McpToolException;
import ci.fcloud.hub.mcp.tool.AbstractReadTool;
import ci.fcloud.hub.mcp.tool.McpTool;
import ci.fcloud.hub.mcp.tool.Schemas;
import ci.fcloud.hub.mcp.tool.ToolResult;
import ci.fcloud.hub.module.Module;
import ci.fcloud.hub.module.ResolvedValue;
import ci.fcloud.hub.module.Variable;
import ci.fcloud.hub.module.VariableResolver;
import org.springframework.stereotype.Component;

/**
 * 解释一个变量为什么取到某个值。
 *
 * <p>这是 MCP 相对 UI 的核心增益：界面上要跨三屏看的继承链，
 * 一次调用给出完整命中路径。</p>
 */
@Component
public class ExplainConfigValueTool extends AbstractReadTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "module": {"type": "string", "description": "模块 code"},
                "key": {"type": "string", "description": "变量 key，如 DB_HOST"},
                "environment": {"type": "string", "description": "环境 code"},
                "application": {"type": "string", "description": "应用 code；共享模块的应用级覆盖需要它"}
              },
              "required": ["module", "key", "environment"]
            }
            """);

    @Override
    public String name() {
        return "explain_config_value";
    }

    @Override
    public String description() {
        return "解释某个变量在指定环境下取到的值来自哪一层（应用级覆盖 / 模块级覆盖 / 父环境继承 / 全局值），"
                + "并给出完整解析链。排障「为什么 prod 是这个值」时优先用它。不返回加密变量明文。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, McpContext ctx) {
        String appCode = optStr(args, "application");
        String key = requireStr(args, "key");
        Module m = requireModule(requireStr(args, "module"), appCode);
        Environment env = requireEnv(requireStr(args, "environment"));
        Long appId = overrideAppId(m, appCode);

        Variable v = variableService.listByModule(m.getId()).stream()
                .filter(x -> x.getVarKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new McpToolException(
                        "模块 " + m.getCode() + " 下不存在变量 " + key
                                + "，先用 get_variable_matrix 查看现有 key"));

        // revealPlain=false：解释来源不需要明文，绝不解密
        ResolvedValue rv = variableResolver.resolveVariable(v, appId, env.getId(), false);
        List<VariableResolver.ChainLevel> chain =
                variableResolver.explain(v, appId, env.getId(), false);

        Map<Long, String> envCodeById = new HashMap<>();
        for (Environment e : environmentService.listAll()) {
            envCodeById.put(e.getId(), e.getCode());
        }

        StringBuilder sb = new StringBuilder();
        if (rv.isFound()) {
            sb.append(v.getVarKey()).append(" = ").append(rv.getValue()).append('\n');
            sb.append("来源：").append(rv.getSource() == null ? "UNKNOWN" : rv.getSource().name());
            if (rv.getSourceEnvironmentId() != null) {
                sb.append("（环境 ").append(envCodeById.get(rv.getSourceEnvironmentId())).append("）");
            }
            sb.append('\n');
        } else {
            sb.append(v.getVarKey()).append(" 在 ").append(env.getCode())
                    .append(" 及其祖先环境、全局值中均无值\n");
        }
        sb.append("解析链（当前环境 → 父环境… → 全局值）：\n");

        List<Map<String, Object>> chainRows = new ArrayList<>();
        for (VariableResolver.ChainLevel level : chain) {
            boolean global = level.getEnvironmentId() == null;
            String envCode = global ? "GLOBAL"
                    : envCodeById.getOrDefault(level.getEnvironmentId(), "?");
            if (level.isDefined()) {
                sb.append("  ").append(global ? "全局值" : envCode).append("  ");
                if (!global) {
                    sb.append(level.getSource()).append("  ");
                }
                sb.append("= ").append(level.getValue()).append('\n');
            } else {
                sb.append("  ").append(global ? "全局值" : envCode).append("  未定义\n");
            }
            chainRows.add(Schemas.row(
                    "environment", envCode,
                    "defined", level.isDefined(),
                    "source", level.getSource(),
                    "value", level.isDefined() ? level.getValue() : null,
                    "masked", level.isMasked()));
        }

        if (m.getApplicationId() == null && appCode == null) {
            sb.append("提示：这是共享模块，未指定 application，因此没有检查应用级覆盖。\n");
        }

        return ToolResult.ok(sb.toString(), Schemas.row(
                "module", m.getCode(),
                "key", v.getVarKey(),
                "environment", env.getCode(),
                "found", rv.isFound(),
                "value", rv.isFound() ? rv.getValue() : null,
                "source", rv.getSource() == null ? null : rv.getSource().name(),
                "sourceEnvironment", rv.getSourceEnvironmentId() == null
                        ? null : envCodeById.get(rv.getSourceEnvironmentId()),
                "masked", rv.isMasked(),
                "chain", chainRows));
    }
}
