package ci.fcloud.manager.mcp.tool.impl;

import java.util.List;
import java.util.Map;

import ci.fcloud.manager.app.Application;
import ci.fcloud.manager.mcp.McpContext;
import ci.fcloud.manager.mcp.McpToolException;
import ci.fcloud.manager.mcp.tool.AbstractReadTool;
import ci.fcloud.manager.mcp.tool.McpTool;
import ci.fcloud.manager.mcp.tool.Schemas;
import ci.fcloud.manager.mcp.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 应用列表。
 */
@Component
public class ListApplicationsTool extends AbstractReadTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "keyword": {"type": "string", "description": "按 code 或名称模糊过滤，可省略"}
              }
            }
            """);

    @Override
    public String name() {
        return "list_applications";
    }

    @Override
    public String description() {
        return "列出配置中心的应用（部署单元），返回 code、名称与模块数量。"
                + "开始任何配置查询前先用它确认应用 code，不要臆造。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, McpContext ctx) {
        String keyword = optStr(args, "keyword");
        List<Application> apps = allApplications().stream()
                .filter(a -> keyword == null
                        || contains(a.getCode(), keyword)
                        || contains(a.getName(), keyword))
                .toList();

        if (apps.isEmpty()) {
            throw new McpToolException(keyword == null
                    ? "配置中心还没有任何应用"
                    : "没有匹配「" + keyword + "」的应用");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(apps.size()).append(" 个应用：\n");
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Application a : apps) {
            int moduleCount = moduleService.listByApplication(a.getId()).size();
            sb.append("- ").append(a.getCode())
                    .append("（").append(a.getName()).append("）")
                    .append(" · ").append(moduleCount).append(" 个模块");
            if (a.getDescription() != null && !a.getDescription().isBlank()) {
                sb.append(" · ").append(a.getDescription());
            }
            sb.append('\n');
            rows.add(Schemas.row(
                    "code", a.getCode(),
                    "name", a.getName(),
                    "description", a.getDescription(),
                    "moduleCount", moduleCount));
        }
        return ToolResult.ok(sb.toString(), Schemas.row("total", apps.size(), "applications", rows));
    }

    private boolean contains(String text, String keyword) {
        return text != null && text.toLowerCase().contains(keyword.toLowerCase());
    }
}
