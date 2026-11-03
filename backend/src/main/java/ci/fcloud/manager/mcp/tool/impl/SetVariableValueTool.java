package ci.fcloud.manager.mcp.tool.impl;

import java.util.Map;

import ci.fcloud.manager.env.Environment;
import ci.fcloud.manager.mcp.McpContext;
import ci.fcloud.manager.mcp.McpToolException;
import ci.fcloud.manager.mcp.tool.AbstractWriteTool;
import ci.fcloud.manager.mcp.tool.McpTool;
import ci.fcloud.manager.mcp.tool.Schemas;
import ci.fcloud.manager.mcp.tool.ToolResult;
import ci.fcloud.manager.module.Module;
import ci.fcloud.manager.module.Variable;
import ci.fcloud.manager.module.VariableService;
import org.springframework.stereotype.Component;

/**
 * 写入单个变量的值。
 *
 * <p>刻意不做隐式创建：key 不存在直接报错。AI 一次笔误就凭空多出一个配置项，
 * 是最难排查的污染。</p>
 */
@Component
public class SetVariableValueTool extends AbstractWriteTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "module": {"type": "string", "description": "模块 code"},
                "key": {"type": "string", "description": "变量 key；必须已存在"},
                "scope": {"type": "string", "enum": ["GLOBAL", "ENV"], "description": "写入作用域"},
                "environment": {"type": "string", "description": "scope=ENV 时必填"},
                "value": {"type": "string", "description": "新值；空串表示清除该作用域的值（回退继承）"},
                "application": {"type": "string", "description": "应用 code；共享模块的应用级覆盖需要它"}
              },
              "required": ["module", "key", "scope", "value"]
            }
            """);

    @Override
    public String name() {
        return "set_variable_value";
    }

    @Override
    public String description() {
        return "修改一个已存在变量的值：scope=GLOBAL 改全局值，scope=ENV 写环境覆盖（空串清除覆盖、回退继承）。"
                + "key 不存在会报错，需要新建请用 create_variable。批量改多个 key 用 import_dotenv 更省事。";
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
        Variable v = requireVariable(m.getId(), requireStr(args, "key"));

        String target = requireStr(args, "scope").toUpperCase();
        if (!"GLOBAL".equals(target) && !"ENV".equals(target)) {
            throw new McpToolException("scope 必须是 GLOBAL 或 ENV");
        }
        if (!args.containsKey("value")) {
            throw new McpToolException("缺少必填参数: value");
        }
        Object raw = args.get("value");
        String value = raw == null ? null : String.valueOf(raw);

        Long envId = null;
        String envCode = null;
        if ("ENV".equals(target)) {
            Environment env = requireEnv(requireStr(args, "environment"));
            envId = env.getId();
            envCode = env.getCode();
            // 写一个模块没绑定的环境，流水线永远读不到，属于典型的"静默无效"
            if (!moduleService.environmentIdsOf(m.getId()).contains(envId)) {
                throw new McpToolException("模块 " + m.getCode() + " 未绑定环境 " + envCode
                        + "，不能写入该环境的覆盖");
            }
        }

        Long appId = overrideAppId(m, appCode);

        // 全局值幂等：同值重复写不产生审计噪音（加密变量无法安全比较，跳过）
        if ("GLOBAL".equals(target) && !"ENCRYPTED".equals(v.getValueType())) {
            String current = v.getGlobalValue();
            if (java.util.Objects.equals(current, value)) {
                return ToolResult.ok("未变更：" + v.getVarKey() + " 的全局值已是该值",
                        Schemas.row("changed", false, "key", v.getVarKey(), "scope", "GLOBAL"));
            }
        }

        variableService.setValue(m.getId(), v.getId(),
                new VariableService.ValueRequest(target, envId, appId, value));

        audit("MCP_SET_VALUE", "VARIABLE", String.valueOf(v.getId()), false, Schemas.row(
                "via", "MCP",
                "patId", ctx.principal().tokenId(),
                "module", m.getCode(),
                "key", v.getVarKey(),
                "scope", target,
                "environment", envCode,
                "cleared", value == null || value.isEmpty()));

        String action = (value == null || value.isEmpty()) ? "已清除" : "已写入";
        String where = "GLOBAL".equals(target) ? "全局值" : "环境 " + envCode + " 的覆盖";
        return ToolResult.ok(action + "：" + v.getVarKey() + " @ " + where,
                Schemas.row("changed", true, "key", v.getVarKey(), "scope", target,
                        "environment", envCode, "cleared", value == null || value.isEmpty()));
    }
}
