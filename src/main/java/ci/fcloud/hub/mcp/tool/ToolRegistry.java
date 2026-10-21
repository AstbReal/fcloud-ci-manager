package ci.fcloud.hub.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ci.fcloud.hub.mcp.McpPrincipal;
import ci.fcloud.hub.mcp.McpSettingService;
import ci.fcloud.hub.mcp.McpToolException;
import org.springframework.stereotype.Component;

/**
 * 工具注册表。
 *
 * <p>按调用方 scope 过滤——无权限的工具**不出现在 tools/list 里**，
 * 而不是调用时才报错。模型看不到就不会选，避免无谓的重试。</p>
 *
 * <p>服务端开关（read 常开 / write、secret 可关）走 {@link McpSettingService}，
 * 页面改完 5 秒内生效，不需要重启。</p>
 */
@Component
public class ToolRegistry {

    private final List<McpTool> tools;
    private final Map<String, McpTool> index;
    private final McpSettingService settings;

    public ToolRegistry(List<McpTool> tools, McpSettingService settings) {
        this.tools = List.copyOf(tools);
        this.settings = settings;
        Map<String, McpTool> m = new LinkedHashMap<>();
        for (McpTool t : this.tools) {
            if (m.putIfAbsent(t.name(), t) != null) {
                throw new IllegalStateException("MCP 工具名重复: " + t.name());
            }
        }
        this.index = Map.copyOf(m);
    }

    public List<McpTool> visibleTo(McpPrincipal principal) {
        return tools.stream()
                .filter(t -> principal.scopes().contains(t.scope()))
                .filter(this::enabledByServer)
                .toList();
    }

    public McpTool require(String name, McpPrincipal principal) {
        McpTool t = index.get(name);
        if (t == null) {
            throw new McpToolException("未知工具: " + name);
        }
        if (!principal.scopes().contains(t.scope())) {
            throw new McpToolException("当前令牌没有权限调用 " + name + "（需要 " + t.scope() + "）");
        }
        if (!enabledByServer(t)) {
            throw new McpToolException("服务端已关闭该能力（" + t.scope() + "）");
        }
        return t;
    }

    /**
     * 按能力类别统计，供设置页展示"关掉这个开关会停用几个工具"。
     */
    public Map<String, Integer> countByScope() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("read", 0);
        out.put("write", 0);
        out.put("secret", 0);
        for (McpTool t : tools) {
            out.merge(t.scope().replace("mcp:", ""), 1, Integer::sum);
        }
        return out;
    }

    public int size() {
        return tools.size();
    }

    /**
     * 三类能力各自的开关：read 常开、secret 由 revealEnabled 控制、其余（write）由 writeEnabled 控制。
     * 机器主体的 scopes 只有 mcp:read，写工具在上一层就被挡掉。
     */
    private boolean enabledByServer(McpTool t) {
        return switch (t.scope()) {
            case "mcp:read" -> true;
            case "mcp:secret" -> settings.revealEnabled();
            default -> settings.writeEnabled();
        };
    }
}
