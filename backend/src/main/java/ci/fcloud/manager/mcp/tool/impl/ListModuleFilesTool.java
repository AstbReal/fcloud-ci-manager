package ci.fcloud.manager.mcp.tool.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ci.fcloud.manager.env.Environment;
import ci.fcloud.manager.file.FileVersion;
import ci.fcloud.manager.file.ModuleFile;
import ci.fcloud.manager.mcp.McpContext;
import ci.fcloud.manager.mcp.McpToolException;
import ci.fcloud.manager.mcp.tool.AbstractReadTool;
import ci.fcloud.manager.mcp.tool.McpTool;
import ci.fcloud.manager.mcp.tool.Schemas;
import ci.fcloud.manager.mcp.tool.ToolResult;
import ci.fcloud.manager.module.Module;
import org.springframework.stereotype.Component;

/**
 * 模块在某环境下的文件清单。
 */
@Component
public class ListModuleFilesTool extends AbstractReadTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "module": {"type": "string", "description": "模块 code"},
                "environment": {"type": "string", "description": "环境 code"},
                "application": {"type": "string", "description": "应用 code；模块 code 重名时用于消歧"}
              },
              "required": ["module", "environment"]
            }
            """);

    @Override
    public String name() {
        return "list_module_files";
    }

    @Override
    public String description() {
        return "列出模块在指定环境下挂载的文件（fileKey、当前版本、大小、类型、更新时间）。"
                + "只看清单，不下载内容。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, McpContext ctx) {
        String appCode = optStr(args, "application");
        Module m = requireModule(requireStr(args, "module"), appCode);
        Environment env = requireEnv(requireStr(args, "environment"));

        List<ModuleFile> files = fileService.listByModule(m.getId(), env.getId());
        if (files.isEmpty()) {
            throw new McpToolException("模块 " + m.getCode() + " 在环境 " + env.getCode() + " 下没有文件");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("模块 ").append(m.getCode()).append(" · 环境 ").append(env.getCode())
                .append(" · ").append(files.size()).append(" 个文件：\n");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ModuleFile f : files) {
            FileVersion v = fileService.resolveVersion(f.getId(), null);
            sb.append("- ").append(f.getFileKey())
                    .append("  v").append(v.getVersion())
                    .append("  ").append(humanSize(v.getSize()))
                    .append("  ").append(v.getMimeType() == null ? "application/octet-stream" : v.getMimeType());
            if (f.getRemark() != null && !f.getRemark().isBlank()) {
                sb.append("  // ").append(f.getRemark());
            }
            sb.append('\n');
            rows.add(Schemas.row(
                    "fileKey", f.getFileKey(),
                    "version", v.getVersion(),
                    "size", v.getSize(),
                    "mimeType", v.getMimeType(),
                    "originalName", v.getOriginalName(),
                    "updatedAt", v.getCreatedAt() == null ? null : v.getCreatedAt().toString(),
                    "remark", f.getRemark()));
        }
        return ToolResult.ok(sb.toString(), Schemas.row(
                "module", m.getCode(),
                "environment", env.getCode(),
                "files", rows));
    }

    private String humanSize(Long bytes) {
        if (bytes == null) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
