package ci.fcloud.manager.mcp.tool.impl;

import java.util.Map;

import ci.fcloud.manager.mcp.McpContext;
import ci.fcloud.manager.mcp.McpToolException;
import ci.fcloud.manager.mcp.tool.AbstractWriteTool;
import ci.fcloud.manager.mcp.tool.McpTool;
import ci.fcloud.manager.mcp.tool.Schemas;
import ci.fcloud.manager.mcp.tool.ToolResult;
import ci.fcloud.manager.module.Module;
import ci.fcloud.manager.module.Variable;
import org.springframework.stereotype.Component;

/**
 * 新建变量定义（含可选初始值）。
 */
@Component
public class CreateVariableTool extends AbstractWriteTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "module": {"type": "string", "description": "模块 code"},
                "key": {"type": "string", "description": "变量 key，模块内唯一"},
                "valueType": {"type": "string", "enum": ["PLAIN", "ENCRYPTED"], "description": "默认 PLAIN；ENCRYPTED 会在入库前加密"},
                "globalValue": {"type": "string", "description": "可选初始全局值"},
                "remark": {"type": "string", "description": "备注"},
                "isRequired": {"type": "boolean", "description": "是否必填"},
                "application": {"type": "string", "description": "应用 code；模块 code 重名时用于消歧"}
              },
              "required": ["module", "key"]
            }
            """);

    @Override
    public String name() {
        return "create_variable";
    }

    @Override
    public String description() {
        return "在模块里新建一个变量定义（可选带初始全局值）。key 已存在会冲突报错。"
                + "只想改已有变量用 set_variable_value；批量导入用 import_dotenv。";
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

        String appCode = optStr(args, "application");
        Module m = requireModule(requireStr(args, "module"), appCode);
        String key = requireStr(args, "key");

        String valueType = optStr(args, "valueType");
        if (valueType == null) {
            valueType = "PLAIN";
        }
        valueType = valueType.toUpperCase();
        if (!"PLAIN".equals(valueType) && !"ENCRYPTED".equals(valueType)) {
            throw new McpToolException("valueType 必须是 PLAIN 或 ENCRYPTED");
        }

        Variable body = new Variable();
        body.setVarKey(key);
        body.setValueType(valueType);
        body.setRemark(optStr(args, "remark"));
        body.setIsRequired(Boolean.TRUE.equals(args.get("isRequired")));
        body.setGlobalValue(optStr(args, "globalValue"));

        Variable created = variableService.create(m.getId(), body);

        audit("MCP_CREATE_VARIABLE", "VARIABLE", String.valueOf(created.getId()), false, Schemas.row(
                "via", "MCP",
                "patId", ctx.principal().tokenId(),
                "module", m.getCode(),
                "key", created.getVarKey(),
                "valueType", created.getValueType(),
                "hasInitialValue", created.getGlobalValue() != null));

        return ToolResult.ok("已新建变量 " + created.getVarKey()
                        + "（" + created.getValueType() + "，模块 " + m.getCode() + "）",
                Schemas.row("id", created.getId(), "key", created.getVarKey(),
                        "valueType", created.getValueType(),
                        "hasGlobalValue", created.getGlobalValue() != null));
    }
}
