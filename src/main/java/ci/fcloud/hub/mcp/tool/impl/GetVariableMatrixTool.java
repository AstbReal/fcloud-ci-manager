package ci.fcloud.hub.mcp.tool.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 变量矩阵：行=变量，列=全局值 + 各绑定环境的解析结果。
 *
 * <p>加密变量**永远**返回脱敏占位符——不解密、不返回明文。
 * 需要明文请走 reveal_secret（M2，另需 mcp:secret scope）。</p>
 */
@Component
public class GetVariableMatrixTool extends AbstractReadTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "module": {"type": "string", "description": "模块 code"},
                "application": {"type": "string", "description": "应用 code；共享模块的应用级覆盖需要它"},
                "environments": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "只看这些环境 code，省略则返回全部已绑定环境"
                }
              },
              "required": ["module"]
            }
            """);

    @Override
    public String name() {
        return "get_variable_matrix";
    }

    @Override
    public String description() {
        return "查看模块的变量矩阵：每个 key 的类型、全局值、以及在各环境的最终取值与来源。"
                + "用来理解一个模块有哪些配置项。加密变量显示为 ••••••，这是正常现象，不要重试。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, McpContext ctx) {
        String appCode = optStr(args, "application");
        Module m = requireModule(requireStr(args, "module"), appCode);
        Long appId = overrideAppId(m, appCode);

        List<Long> bound = moduleService.environmentIdsOf(m.getId());
        List<String> envFilter = optList(args, "environments");
        List<Environment> envs = environmentService.listAll().stream()
                .filter(e -> bound.contains(e.getId()))
                .filter(e -> envFilter.isEmpty() || envFilter.contains(e.getCode()))
                .toList();
        if (envs.isEmpty()) {
            throw new McpToolException("模块 " + m.getCode() + " 没有绑定任何环境"
                    + (envFilter.isEmpty() ? "" : "（或指定的环境未绑定）"));
        }

        List<Variable> vars = variableService.listByModule(m.getId());
        if (vars.isEmpty()) {
            throw new McpToolException("模块 " + m.getCode() + " 还没有定义任何变量");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("模块 ").append(m.getCode()).append(" · ").append(vars.size())
                .append(" 个变量 · 环境: ")
                .append(String.join(",", envs.stream().map(Environment::getCode).toList()))
                .append('\n');

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Variable v : vars) {
            boolean encrypted = "ENCRYPTED".equals(v.getValueType());
            Map<String, Object> globalCell = null;
            StringBuilder line = new StringBuilder();
            line.append(v.getVarKey()).append("  ").append(v.getValueType());

            if (v.getGlobalValue() != null) {
                String shown = encrypted ? VariableResolver.MASKED_PLACEHOLDER : v.getGlobalValue();
                globalCell = Schemas.row("value", shown, "masked", encrypted, "defined", true);
                line.append("  global=").append(shown);
            }

            Map<String, Object> cells = new LinkedHashMap<>();
            for (Environment e : envs) {
                ResolvedValue rv = variableResolver.resolveVariable(v, appId, e.getId(), false);
                Map<String, Object> cell = Schemas.row(
                        "value", rv.isFound() ? rv.getValue() : null,
                        "found", rv.isFound(),
                        "source", rv.getSource() == null ? null : rv.getSource().name(),
                        "masked", rv.isMasked(),
                        "inherited", rv.isInherited());
                cells.put(e.getCode(), cell);
                line.append("  ").append(e.getCode()).append('=');
                if (!rv.isFound()) {
                    line.append("—");
                } else {
                    line.append(rv.getValue());
                    if (rv.getSource() != null) {
                        line.append('(').append(rv.getSource().name()).append(')');
                    }
                }
            }
            if (v.getRemark() != null && !v.getRemark().isBlank()) {
                line.append("  // ").append(v.getRemark());
            }
            sb.append(line).append('\n');

            rows.add(Schemas.row(
                    "key", v.getVarKey(),
                    "type", v.getValueType(),
                    "required", Boolean.TRUE.equals(v.getIsRequired()),
                    "remark", v.getRemark(),
                    "global", globalCell,
                    "environments", cells));
        }

        return ToolResult.ok(sb.toString(), Schemas.row(
                "module", m.getCode(),
                "environments", envs.stream().map(Environment::getCode).toList(),
                "variables", rows));
    }
}
