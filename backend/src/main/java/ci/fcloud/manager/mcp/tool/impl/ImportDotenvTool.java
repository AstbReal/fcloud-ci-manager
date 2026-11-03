package ci.fcloud.manager.mcp.tool.impl;

import java.util.Map;

import ci.fcloud.manager.mcp.McpContext;
import ci.fcloud.manager.mcp.McpToolException;
import ci.fcloud.manager.mcp.tool.AbstractWriteTool;
import ci.fcloud.manager.mcp.tool.McpTool;
import ci.fcloud.manager.mcp.tool.Schemas;
import ci.fcloud.manager.mcp.tool.ToolResult;
import ci.fcloud.manager.module.Module;
import ci.fcloud.manager.module.VariableImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 批量导入 dotenv。
 *
 * <p><b>默认 dryRun=true</b>：不写库，只回答"会发生什么"。要真正落地必须显式传
 * {@code dryRun:false}。这是唯一一处默认不执行的写工具——批量写值一旦错了，
 * 回滚成本远高于多问一轮。</p>
 */
@Component
public class ImportDotenvTool extends AbstractWriteTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "module": {"type": "string", "description": "模块 code"},
                "dotenvText": {"type": "string", "description": "dotenv 文本，KEY=VALUE 每行一个"},
                "applyTo": {"type": "string", "enum": ["GLOBAL", "ENV"], "description": "写到全局值还是环境覆盖"},
                "environment": {"type": "string", "description": "applyTo=ENV 时必填"},
                "application": {"type": "string", "description": "应用 code；共享模块需要"},
                "dryRun": {"type": "boolean", "description": "默认 true（只预演）；要真正写入必须传 false"}
              },
              "required": ["module", "dotenvText", "applyTo"]
            }
            """);

    /** 类型推断同 console 导入：PASSWORD/SECRET/TOKEN/KEY 结尾默认加密 */
    private static final String CREATE = "CREATE";
    private static final String UPDATE = "UPDATE";

    @Autowired
    private VariableImportService importService;

    @Override
    public String name() {
        return "import_dotenv";
    }

    @Override
    public String description() {
        return "把一段 dotenv 批量导入模块。默认 dryRun=true，只返回将发生的新建/更新/跳过明细；"
                + "确认无误后再传 dryRun=false 落地。已存在的 key 只在目标作用域为空时写入，不覆盖已填值。";
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
        String text = requireStr(args, "dotenvText");
        String applyTo = requireStr(args, "applyTo").toUpperCase();

        Long envId = null;
        String envCode = null;
        if ("ENV".equals(applyTo)) {
            var env = requireEnv(requireStr(args, "environment"));
            envId = env.getId();
            envCode = env.getCode();
            if (!moduleService.environmentIdsOf(m.getId()).contains(envId)) {
                throw new McpToolException("模块 " + m.getCode() + " 未绑定环境 " + envCode);
            }
        } else if (!"GLOBAL".equals(applyTo)) {
            throw new McpToolException("applyTo 必须是 GLOBAL 或 ENV");
        }

        boolean dryRun = true;
        if (args.containsKey("dryRun")) {
            Object raw = args.get("dryRun");
            dryRun = raw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw));
        }

        VariableImportService.Outcome outcome =
                importService.importDotenv(m.getId(), text, applyTo, envId, dryRun);

        StringBuilder sb = new StringBuilder();
        sb.append(outcome.summary()).append('\n');
        for (VariableImportService.Item item : outcome.items()) {
            sb.append("  ").append(item.action()).append(' ')
                    .append(item.key()).append(" (").append(item.valueType()).append(") - ")
                    .append(item.reason()).append('\n');
        }
        if (dryRun) {
            sb.append("这是预演，未写入任何数据。确认后传 dryRun=false 执行。\n");
        }

        if (!dryRun && (outcome.created() + outcome.updated()) > 0) {
            audit("MCP_IMPORT_DOTENV", "MODULE", String.valueOf(m.getId()), false, Schemas.row(
                    "via", "MCP",
                    "patId", ctx.principal().tokenId(),
                    "module", m.getCode(),
                    "applyTo", applyTo,
                    "environment", envCode,
                    "created", outcome.created(),
                    "updated", outcome.updated(),
                    "skipped", outcome.skipped()));
        }

        return ToolResult.ok(sb.toString(), Schemas.row(
                "module", m.getCode(),
                "applyTo", applyTo,
                "environment", envCode,
                "dryRun", dryRun,
                "created", outcome.created(),
                "updated", outcome.updated(),
                "skipped", outcome.skipped(),
                "items", outcome.items().stream().map(i -> Schemas.row(
                        "key", i.key(),
                        "action", i.action(),
                        "valueType", i.valueType(),
                        "reason", i.reason())).toList()));
    }
}
