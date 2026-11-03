package ci.fcloud.manager.mcp.tool;

import java.util.Map;

import ci.fcloud.manager.audit.AuditService;
import ci.fcloud.manager.mcp.McpContext;
import ci.fcloud.manager.mcp.McpToolException;
import ci.fcloud.manager.module.PermissionService;
import ci.fcloud.manager.module.Variable;

/**
 * 写工具基类。
 *
 * <p>三条硬规则：</p>
 * <ol>
 *   <li>机器主体（流水线 Token）一律拒绝——写操作必须能追到人；</li>
 *   <li>必须有 mcp:write（reveal_secret 用 mcp:secret，自行覆写校验）；</li>
 *   <li>审计 detail 只记 key / 作用域，<b>绝不记值</b>。</li>
 * </ol>
 */
public abstract class AbstractWriteTool extends AbstractReadTool {

    @org.springframework.beans.factory.annotation.Autowired
    protected AuditService auditService;
    @org.springframework.beans.factory.annotation.Autowired
    protected PermissionService permissionService;

    protected void requireHuman(McpContext ctx) {
        if (ctx.isMachine()) {
            throw new McpToolException(
                    "写操作需要个人访问令牌（chub_pat_）；流水线 Token 只能读取配置");
        }
    }

    protected void requireWriteScope(McpContext ctx) {
        requireHuman(ctx);
        if (!ctx.principal().canWrite()) {
            throw new McpToolException("当前令牌没有写权限，请用带 mcp:write 的访问令牌");
        }
    }

    protected Variable requireVariable(Long moduleId, String key) {
        return variableService.listByModule(moduleId).stream()
                .filter(v -> v.getVarKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new McpToolException(
                        "模块下不存在变量 " + key + "，先用 get_variable_matrix 查看现有 key"
                                + "，或调用 create_variable 新建"));
    }

    /**
     * 审计落库。detail 里只允许出现标识与元数据，禁止出现变量值 / 文件内容。
     */
    protected void audit(String action, String targetType, String targetId, boolean critical,
                         Map<String, Object> detail) {
        auditService.log(action, targetType, targetId, critical,
                auditService.detailJson(detail.entrySet().stream()
                        .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
                        .toArray()));
    }
}
