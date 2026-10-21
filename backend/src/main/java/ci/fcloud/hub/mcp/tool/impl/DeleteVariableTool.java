package ci.fcloud.hub.mcp.tool.impl;

import java.util.Map;

import ci.fcloud.hub.mcp.McpContext;
import ci.fcloud.hub.mcp.McpToolException;
import ci.fcloud.hub.mcp.tool.AbstractWriteTool;
import ci.fcloud.hub.mcp.tool.McpTool;
import ci.fcloud.hub.mcp.tool.Schemas;
import ci.fcloud.hub.mcp.tool.ToolResult;
import ci.fcloud.hub.module.Module;
import ci.fcloud.hub.module.Variable;
import org.springframework.stereotype.Component;

/**
 * 删除变量定义（连带覆盖），仅系统管理员。
 */
@Component
public class DeleteVariableTool extends AbstractWriteTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "module": {"type": "string", "description": "模块 code"},
                "key": {"type": "string", "description": "变量 key"},
                "application": {"type": "string", "description": "应用 code；模块 code 重名时用于消歧"}
              },
              "required": ["module", "key"]
            }
            """);

    @Override
    public String name() {
        return "delete_variable";
    }

    @Override
    public String description() {
        return "删除变量定义及其全部环境覆盖，不可撤销，仅系统管理员可调用。"
                + "只是想让某个环境回退继承，用 set_variable_value 传空值清除覆盖，不要删定义。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public String scope() {
        return "mcp:write";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, McpContext ctx) {
        requireWriteScope(ctx);
        requireHuman(ctx);
        if (!permissionService.canDelete()) {
            throw new McpToolException("只有系统管理员可以删除变量");
        }

        String appCode = optStr(args, "application");
        Module m = requireModule(requireStr(args, "module"), appCode);
        Variable v = requireVariable(m.getId(), requireStr(args, "key"));

        variableService.delete(m.getId(), v.getId());

        audit("MCP_DELETE_VARIABLE", "VARIABLE", String.valueOf(v.getId()), true, Schemas.row(
                "via", "MCP",
                "patId", ctx.principal().tokenId(),
                "module", m.getCode(),
                "key", v.getVarKey(),
                "valueType", v.getValueType()));

        return ToolResult.ok("已删除变量 " + v.getVarKey() + "（模块 " + m.getCode() + "）",
                Schemas.row("deleted", true, "key", v.getVarKey()));
    }
}
