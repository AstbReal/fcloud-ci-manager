package ci.fcloud.manager.mcp.tool.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ci.fcloud.manager.app.Application;
import ci.fcloud.manager.env.Environment;
import ci.fcloud.manager.mcp.McpContext;
import ci.fcloud.manager.mcp.McpToolException;
import ci.fcloud.manager.mcp.tool.AbstractReadTool;
import ci.fcloud.manager.mcp.tool.McpTool;
import ci.fcloud.manager.mcp.tool.Schemas;
import ci.fcloud.manager.mcp.tool.ToolResult;
import ci.fcloud.manager.module.Module;
import org.springframework.stereotype.Component;

/**
 * 模块列表。
 */
@Component
public class ListModulesTool extends AbstractReadTool implements McpTool {

    private static final int MAX_ROWS = 300;

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "application": {"type": "string", "description": "应用 code；只看某个应用的模块时传"},
                "shared": {"type": "boolean", "description": "true 只看共享模块"}
              }
            }
            """);

    @Override
    public String name() {
        return "list_modules";
    }

    @Override
    public String description() {
        return "列出模块（配置单元），返回 code、所属应用、是否共享、绑定的环境。"
                + "不确定模块 code 时先调它，不要猜。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, McpContext ctx) {
        String appCode = optStr(args, "application");
        boolean onlyShared = Boolean.TRUE.equals(args.get("shared"));

        List<Module> modules;
        if (onlyShared) {
            modules = moduleService.listShared();
        } else if (appCode != null) {
            Application app = requireApp(appCode);
            modules = moduleService.listByApplication(app.getId());
        } else {
            modules = moduleService.lambdaQuery()
                    .orderByAsc(Module::getSortOrder)
                    .orderByAsc(Module::getId)
                    .list();
        }

        if (modules.isEmpty()) {
            throw new McpToolException("没有匹配的模块");
        }

        Map<Long, String> appCodeById = new HashMap<>();
        for (Application a : allApplications()) {
            appCodeById.put(a.getId(), a.getCode());
        }
        Map<Long, String> envCodeById = new HashMap<>();
        for (Environment e : environmentService.listAll()) {
            envCodeById.put(e.getId(), e.getCode());
        }

        boolean truncated = modules.size() > MAX_ROWS;
        List<Module> shown = truncated ? modules.subList(0, MAX_ROWS) : modules;

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(modules.size()).append(" 个模块");
        if (truncated) {
            sb.append("（仅列出前 ").append(MAX_ROWS).append(" 个）");
        }
        sb.append("：\n");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Module m : shown) {
            List<String> envCodes = moduleService.environmentIdsOf(m.getId()).stream()
                    .map(envCodeById::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            String owner = m.getApplicationId() == null
                    ? "共享" : appCodeById.getOrDefault(m.getApplicationId(), "?");
            sb.append("- ").append(m.getCode())
                    .append("（").append(m.getName()).append("）")
                    .append(" · ").append(owner)
                    .append(" · env: ").append(envCodes.isEmpty() ? "未绑定" : String.join(",", envCodes));
            if (m.getGroupName() != null && !m.getGroupName().isBlank()) {
                sb.append(" · 分组: ").append(m.getGroupName());
            }
            sb.append('\n');
            rows.add(Schemas.row(
                    "id", m.getId(),
                    "code", m.getCode(),
                    "name", m.getName(),
                    "application", m.getApplicationId() == null ? null : appCodeById.get(m.getApplicationId()),
                    "shared", Boolean.TRUE.equals(m.getIsShared()),
                    "environments", envCodes,
                    "group", m.getGroupName()));
        }
        return ToolResult.ok(sb.toString(),
                Schemas.row("total", modules.size(), "truncated", truncated, "modules", rows));
    }
}
