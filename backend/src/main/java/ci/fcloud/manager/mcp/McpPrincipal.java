package ci.fcloud.manager.mcp;

import java.util.Set;

/**
 * MCP 调用主体。
 *
 * <p>两类主体严格区分，不允许混用：</p>
 * <ul>
 *   <li>{@link Kind#MACHINE}：流水线 Token（chub_），全局只读，没有真实用户。
 *       写工具必须拒绝，否则审计里查不到责任人。</li>
 *   <li>{@link Kind#USER}：个人访问令牌（chub_pat_），绑定真实用户，
 *       权限跟随该用户的全局角色 + PAT scope。</li>
 * </ul>
 */
public record McpPrincipal(Kind kind, Long userId, String label,
                           Set<String> scopes, Long tokenId) {

    public enum Kind {
        USER, MACHINE
    }

    public static McpPrincipal machine(Long tokenId, String tokenPrefix) {
        return new McpPrincipal(Kind.MACHINE, 0L, tokenPrefix, Set.of("mcp:read"), tokenId);
    }

    public static McpPrincipal user(Long userId, String email, Set<String> scopes, Long patId) {
        return new McpPrincipal(Kind.USER, userId, email, scopes, patId);
    }

    public boolean isMachine() {
        return kind == Kind.MACHINE;
    }

    public boolean canWrite() {
        return scopes.contains("mcp:write");
    }

    public boolean canReveal() {
        return scopes.contains("mcp:secret");
    }
}
