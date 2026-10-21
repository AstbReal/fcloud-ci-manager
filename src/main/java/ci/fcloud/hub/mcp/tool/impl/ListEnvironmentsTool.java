package ci.fcloud.hub.mcp.tool.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ci.fcloud.hub.env.Environment;
import ci.fcloud.hub.mcp.McpContext;
import ci.fcloud.hub.mcp.tool.AbstractReadTool;
import ci.fcloud.hub.mcp.tool.McpTool;
import ci.fcloud.hub.mcp.tool.Schemas;
import ci.fcloud.hub.mcp.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 环境列表（含继承链）。
 */
@Component
public class ListEnvironmentsTool extends AbstractReadTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {}
            }
            """);

    @Override
    public String name() {
        return "list_environments";
    }

    @Override
    public String description() {
        return "列出全部环境及其父环境（继承链）。"
                + "子环境会继承父环境的覆盖值，解释取值来源时需要这个关系。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, McpContext ctx) {
        List<Environment> envs = environmentService.listAll();
        Map<Long, String> codeById = new HashMap<>();
        for (Environment e : envs) {
            codeById.put(e.getId(), e.getCode());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(envs.size()).append(" 个环境：\n");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Environment e : envs) {
            String parent = e.getParentEnvId() == null ? null : codeById.get(e.getParentEnvId());
            sb.append("- ").append(e.getCode()).append("（").append(e.getName()).append("）");
            if (parent != null) {
                sb.append(" ← 继承 ").append(parent);
            } else {
                sb.append(" · 顶层");
            }
            if (Boolean.TRUE.equals(e.getIsDefault())) {
                sb.append(" · 默认");
            }
            sb.append('\n');
            rows.add(Schemas.row(
                    "code", e.getCode(),
                    "name", e.getName(),
                    "parent", parent,
                    "color", e.getColor(),
                    "isDefault", Boolean.TRUE.equals(e.getIsDefault())));
        }
        return ToolResult.ok(sb.toString(), Schemas.row("total", envs.size(), "environments", rows));
    }
}
