package ci.fcloud.hub.mcp;

/**
 * 单次工具调用的上下文。
 */
public record McpContext(McpPrincipal principal, String sessionId) {

    public boolean isMachine() {
        return principal.isMachine();
    }

    public Long userId() {
        return principal.userId();
    }
}
