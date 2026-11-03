package ci.fcloud.manager.mcp.tool.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ci.fcloud.manager.env.Environment;
import ci.fcloud.manager.mcp.McpContext;
import ci.fcloud.manager.mcp.McpToolException;
import ci.fcloud.manager.mcp.tool.AbstractReadTool;
import ci.fcloud.manager.mcp.tool.McpTool;
import ci.fcloud.manager.mcp.tool.Schemas;
import ci.fcloud.manager.mcp.tool.ToolResult;
import ci.fcloud.manager.module.Module;
import ci.fcloud.manager.module.ResolvedValue;
import ci.fcloud.manager.module.Variable;
import org.springframework.stereotype.Component;

/**
 * 两个环境的配置差异。
 */
@Component
public class DiffConfigTool extends AbstractReadTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "module": {"type": "string", "description": "模块 code"},
                "envA": {"type": "string", "description": "基准环境 code"},
                "envB": {"type": "string", "description": "对比环境 code"},
                "application": {"type": "string", "description": "应用 code；共享模块的应用级覆盖需要它"}
              },
              "required": ["module", "envA", "envB"]
            }
            """);

    @Override
    public String name() {
        return "diff_config";
    }

    @Override
    public String description() {
        return "对比同一模块在两个环境下的最终配置差异：仅 A 有、仅 B 有、值不同。"
                + "预发 vs 生产巡检、发布前核对时用。加密变量无法比较，会单独列出。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, McpContext ctx) {
        String appCode = optStr(args, "application");
        Module m = requireModule(requireStr(args, "module"), appCode);
        Environment a = requireEnv(requireStr(args, "envA"));
        Environment b = requireEnv(requireStr(args, "envB"));
        if (a.getId().equals(b.getId())) {
            throw new McpToolException("envA 与 envB 是同一个环境");
        }
        Long appId = overrideAppId(m, appCode);

        List<Variable> vars = variableService.listByModule(m.getId());
        if (vars.isEmpty()) {
            throw new McpToolException("模块 " + m.getCode() + " 还没有定义任何变量");
        }

        List<Map<String, Object>> onlyA = new ArrayList<>();
        List<Map<String, Object>> onlyB = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        List<Map<String, Object>> opaque = new ArrayList<>();
        int same = 0;

        for (Variable v : vars) {
            ResolvedValue ra = variableResolver.resolveVariable(v, appId, a.getId(), false);
            ResolvedValue rb = variableResolver.resolveVariable(v, appId, b.getId(), false);

            if (!ra.isFound() && !rb.isFound()) {
                continue;
            }
            if (ra.isFound() && !rb.isFound()) {
                onlyA.add(Schemas.row("key", v.getVarKey(), "value", ra.getValue()));
                continue;
            }
            if (!ra.isFound() && rb.isFound()) {
                onlyB.add(Schemas.row("key", v.getVarKey(), "value", rb.getValue()));
                continue;
            }
            if (ra.isMasked() && rb.isMasked()) {
                // 两边都是密文占位符，无法判断是否相等——如实说明，不要假装相同
                opaque.add(Schemas.row("key", v.getVarKey()));
                continue;
            }
            if (!Objects.equals(ra.getValue(), rb.getValue())) {
                changed.add(Schemas.row(
                        "key", v.getVarKey(),
                        a.getCode(), ra.getValue(),
                        b.getCode(), rb.getValue(),
                        "sourceA", ra.getSource() == null ? null : ra.getSource().name(),
                        "sourceB", rb.getSource() == null ? null : rb.getSource().name()));
            } else {
                same++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("模块 ").append(m.getCode()).append("  ")
                .append(a.getCode()).append(" vs ").append(b.getCode()).append('\n');
        sb.append("仅 ").append(a.getCode()).append(" 有：").append(onlyA.size()).append('\n');
        onlyA.forEach(r -> sb.append("  ").append(r.get("key")).append('=').append(r.get("value")).append('\n'));
        sb.append("仅 ").append(b.getCode()).append(" 有：").append(onlyB.size()).append('\n');
        onlyB.forEach(r -> sb.append("  ").append(r.get("key")).append('=').append(r.get("value")).append('\n'));
        sb.append("值不同：").append(changed.size()).append('\n');
        changed.forEach(r -> sb.append("  ").append(r.get("key")).append("  ")
                .append(r.get(a.getCode())).append(" → ").append(r.get(b.getCode())).append('\n'));
        if (!opaque.isEmpty()) {
            sb.append("加密变量（脱敏，无法比较）：").append(opaque.size()).append('\n');
            opaque.forEach(r -> sb.append("  ").append(r.get("key")).append('\n'));
        }
        sb.append("相同：").append(same).append(" 个 key\n");

        return ToolResult.ok(sb.toString(), Schemas.row(
                "module", m.getCode(),
                "envA", a.getCode(),
                "envB", b.getCode(),
                "onlyInA", onlyA,
                "onlyInB", onlyB,
                "changed", changed,
                "opaque", opaque,
                "same", same));
    }
}
