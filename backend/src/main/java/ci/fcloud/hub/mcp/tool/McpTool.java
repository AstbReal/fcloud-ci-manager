package ci.fcloud.hub.mcp.tool;

import java.util.Map;

import ci.fcloud.hub.mcp.McpContext;

/**
 * 一个 MCP 工具。
 *
 * <p>实现约定：</p>
 * <ul>
 *   <li>name 用 snake_case，与 REST 路径无关；</li>
 *   <li>description 写给模型看：做什么、什么时候用、什么时候不要用，三句以内；</li>
 *   <li>inputSchema 必须是 additionalProperties=false 的 JSON Schema 子集；</li>
 *   <li>业务失败抛 McpToolException，不要返回半成品。</li>
 * </ul>
 */
public interface McpTool {

    String name();

    String description();

    Map<String, Object> inputSchema();

    /** 调用所需 scope，ToolRegistry 据此过滤；默认只读。 */
    default String scope() {
        return "mcp:read";
    }

    ToolResult execute(Map<String, Object> args, McpContext ctx);
}
