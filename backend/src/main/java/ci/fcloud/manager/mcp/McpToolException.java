package ci.fcloud.manager.mcp;

/**
 * 工具级业务失败。
 *
 * <p>抛出后由 McpServer 转成 {@code result.isError = true}，
 * 而不是 JSON-RPC 协议错误——协议错会让客户端直接中断对话，
 * isError 才能把原因回灌给模型让它自我纠正。</p>
 */
public class McpToolException extends RuntimeException {

    public McpToolException(String message) {
        super(message);
    }
}
