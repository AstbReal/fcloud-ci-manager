package ci.fcloud.manager.mcp.tool.impl;

import java.util.Base64;
import java.util.Map;

import ci.fcloud.manager.env.Environment;
import ci.fcloud.manager.file.ModuleFile;
import ci.fcloud.manager.mcp.McpContext;
import ci.fcloud.manager.mcp.McpToolException;
import ci.fcloud.manager.mcp.tool.AbstractWriteTool;
import ci.fcloud.manager.mcp.tool.InlineMultipartFile;
import ci.fcloud.manager.mcp.tool.McpTool;
import ci.fcloud.manager.mcp.tool.Schemas;
import ci.fcloud.manager.mcp.tool.ToolResult;
import ci.fcloud.manager.module.Module;
import org.springframework.stereotype.Component;

/**
 * 上传文件（base64），落新版本。
 *
 * <p>文件内容不进审计、不进日志，只记 fileKey 与字节数。</p>
 */
@Component
public class UploadFileTool extends AbstractWriteTool implements McpTool {

    private static final Map<String, Object> SCHEMA = Schemas.of("""
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "module": {"type": "string", "description": "模块 code"},
                "environment": {"type": "string", "description": "环境 code"},
                "fileKey": {"type": "string", "description": "文件 key，如 nginx.conf"},
                "contentBase64": {"type": "string", "description": "文件内容的 base64 编码"},
                "fileName": {"type": "string", "description": "原始文件名，默认取 fileKey"},
                "mimeType": {"type": "string", "description": "MIME 类型，默认 application/octet-stream"},
                "remark": {"type": "string", "description": "版本备注"},
                "application": {"type": "string", "description": "应用 code；共享模块需要"}
              },
              "required": ["module", "environment", "fileKey", "contentBase64"]
            }
            """);

    @Override
    public String name() {
        return "upload_file";
    }

    @Override
    public String description() {
        return "上传/更新模块在某个环境下的文件（base64 内容），自动落新版本、保留最近 3 版。"
                + "单文件上限由服务端存储配置决定。只改变量请用 set_variable_value，不要用这个。";
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
        Environment env = requireEnv(requireStr(args, "environment"));
        String fileKey = requireStr(args, "fileKey");

        if (!moduleService.environmentIdsOf(m.getId()).contains(env.getId())) {
            throw new McpToolException("模块 " + m.getCode() + " 未绑定环境 " + env.getCode());
        }

        byte[] bytes = decode(requireStr(args, "contentBase64"));
        long maxBytes = props.getStorage().getMaxFileSizeMb() * 1024L * 1024L;
        if (bytes.length > maxBytes) {
            throw new McpToolException("文件 " + bytes.length + " 字节，超过 "
                    + props.getStorage().getMaxFileSizeMb() + "MB 限制");
        }
        if (bytes.length == 0) {
            throw new McpToolException("文件内容为空");
        }

        String fileName = optStr(args, "fileName");
        String mime = optStr(args, "mimeType");
        ModuleFile file = fileService.upload(m.getId(), env.getId(), fileKey,
                new InlineMultipartFile(fileKey,
                        fileName == null ? fileKey : fileName,
                        mime == null ? "application/octet-stream" : mime,
                        bytes),
                optStr(args, "remark"));

        audit("MCP_UPLOAD_FILE", "FILE", String.valueOf(file.getId()), false, Schemas.row(
                "via", "MCP",
                "patId", ctx.principal().tokenId(),
                "module", m.getCode(),
                "environment", env.getCode(),
                "fileKey", fileKey,
                "bytes", bytes.length,
                "version", file.getCurrentVersion()));

        return ToolResult.ok("已上传 " + fileKey + " → 模块 " + m.getCode()
                        + " @ " + env.getCode() + "，当前版本 v" + file.getCurrentVersion()
                        + "（" + bytes.length + " 字节）",
                Schemas.row("fileKey", fileKey, "version", file.getCurrentVersion(),
                        "bytes", bytes.length, "environment", env.getCode()));
    }

    /**
     * 容忍 data URL 前缀（data:image/png;base64,xxx），客户端有时会带。
     */
    private byte[] decode(String raw) {
        String s = raw;
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        try {
            return Base64.getMimeDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new McpToolException("contentBase64 不是合法的 base64");
        }
    }
}
