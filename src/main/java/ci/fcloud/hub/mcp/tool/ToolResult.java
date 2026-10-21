package ci.fcloud.hub.mcp.tool;

import java.util.Map;

/**
 * 工具执行结果。
 *
 * @param text       给模型读的文本（结构化文本 / dotenv / 错误说明）
 * @param structured 给客户端精确解析的结构化数据，可为 null
 * @param isError    true 表示业务失败（不是协议错误）
 */
public record ToolResult(String text, Map<String, Object> structured, boolean isError) {

    public static ToolResult text(String text) {
        return new ToolResult(text, null, false);
    }

    public static ToolResult ok(String text, Map<String, Object> structured) {
        return new ToolResult(text, structured, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, null, true);
    }
}
