package ci.fcloud.hub.mcp.tool.impl;

import java.util.List;
import java.util.Map;

import ci.fcloud.hub.env.Environment;
import ci.fcloud.hub.mcp.McpContext;
import ci.fcloud.hub.mcp.McpSettingService;
import ci.fcloud.hub.mcp.McpToolException;
import ci.fcloud.hub.mcp.tool.AbstractWriteTool;
import ci.fcloud.hub.mcp.tool.McpTool;
import ci.fcloud.hub.mcp.tool.Schemas;
import ci.fcloud.hub.mcp.tool.ToolResult;
import ci.fcloud.hub.module.Module;
import ci.fcloud.hub.module.Variable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 读取加密变量的明文。
 *
 * <p>四个条件缺一不可：服务端开关打开、令牌有 mcp:secret、调用人是管理员
 * （或该变量创建者）、每次调用写高危审计。这是整套 MCP 里风险最高的一步，
 * 默认全关。</p>
 */
@Component
public class RevealSecretTool extends AbstractWriteTool implements McpTool {

    @Autowired
    private McpSettingService mcpSettings;

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "module": {"type": "string", "description": "模块 code"},
                "key": {"type": "string", "description": "加密变量 key"},
                "environment": {"type": "string", "description": "环境 code，省略则取模块绑定的第一个环境"},
                "application": {"type": "string", "description": "应用 code；模块 code 重名时用于消歧"}
              },
              "required": ["module", "key"]
            }
            """);

    @Override
    public String name() {
        return "reveal_secret";
    }

    @Override
    public String description() {
        return "读取加密变量的明文。需要令牌带 mcp:secret、服务端开启明文开关、且调用人是系统管理员，"
                + "每次调用都会写高危审计。仅排障确需时使用，不要在回答里复述密钥。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public String scope() {
        return "mcp:secret";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, McpContext ctx) {
        requireHuman(ctx);
        if (!mcpSettings.revealEnabled()) {
            throw new McpToolException("服务端已关闭明文读取（fcloud.mcp.reveal-enabled=false）");
        }
        if (!ctx.principal().canReveal()) {
            throw new McpToolException("当前令牌没有明文权限（需要 mcp:secret）");
        }

        String appCode = optStr(args, "application");
        Module m = requireModule(requireStr(args, "module"), appCode);
        Variable v = requireVariable(m.getId(), requireStr(args, "key"));

        if (!"ENCRYPTED".equals(v.getValueType())) {
            throw new McpToolException("变量 " + v.getVarKey()
                    + " 是 PLAIN 类型，直接用 get_config 或 get_variable_matrix 读取即可");
        }
        if (!permissionService.canRevealEncrypted(m.getId(), v.getCreatedBy())) {
            throw new McpToolException("无权查看该加密变量的明文（需系统管理员或变量创建者）");
        }

        Environment env;
        String envCode = optStr(args, "environment");
        if (envCode != null) {
            env = requireEnv(envCode);
        } else {
            List<Long> bound = moduleService.environmentIdsOf(m.getId());
            if (bound.isEmpty()) {
                throw new McpToolException("模块 " + m.getCode() + " 未绑定环境，无法确定取值环境");
            }
            env = environmentService.requireById(bound.get(0));
        }

        String plain = variableService.reveal(m.getId(), v.getId(), env.getId());

        audit("MCP_REVEAL_SECRET", "VARIABLE", String.valueOf(v.getId()), true, Schemas.row(
                "via", "MCP",
                "patId", ctx.principal().tokenId(),
                "module", m.getCode(),
                "key", v.getVarKey(),
                "environment", env.getCode()));

        return ToolResult.ok(plain, Schemas.row(
                "key", v.getVarKey(),
                "environment", env.getCode(),
                "revealed", true,
                "warning", "明文已返回，请勿在回答中复述"));
    }
}
