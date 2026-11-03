package ci.fcloud.manager.pat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import ci.fcloud.manager.audit.AuditService;
import ci.fcloud.manager.common.ApiResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 个人访问令牌（AI 客户端用）。
 *
 * <p>路径在 {@code /api/console/pat}（非 admin 前缀），
 * 因此任何登录用户都能管理自己的令牌——这是刻意设计：
 * AI 代表的是人，不是管理员特权。</p>
 */
@RestController
@RequestMapping("/api/console/pat")
@RequiredArgsConstructor
public class PatController {

    private final PatService patService;
    private final AuditService auditService;

    /** 只返回当前用户自己的令牌。 */
    @GetMapping
    public ApiResponse<List<PatVO>> list() {
        return ApiResponse.ok(patService.listMine().stream().map(PatVO::of).toList());
    }

    /**
     * 签发：明文只返回一次。
     */
    @PostMapping
    public ApiResponse<IssuedVO> create(@RequestBody CreateBody body) {
        PatService.Issued issued = patService.issue(body.getName(), body.getScopes(),
                body.getExpiresAt());
        Pat pat = issued.pat();
        auditService.log("PAT_ISSUE", "PAT", String.valueOf(pat.getId()), false,
                auditService.detailJson(
                        "prefix", pat.getTokenPrefix(),
                        "scopes", pat.getScopes()));
        return ApiResponse.ok(new IssuedVO(PatVO.of(pat), issued.plainText()));
    }

    /**
     * 吊销：立即失效（本地缓存同步删除）。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable Long id) {
        Pat pat = patService.getById(id);
        String prefix = pat == null ? null : pat.getTokenPrefix();
        patService.revoke(id);
        auditService.log("PAT_REVOKE", "PAT", String.valueOf(id), true,
                auditService.detailJson("prefix", prefix));
        return ApiResponse.ok();
    }

    @Data
    public static class CreateBody {
        private String name;
        /** mcp:read / mcp:write / mcp:secret，缺省只读 */
        private List<String> scopes;
        private Instant expiresAt;
    }

    @Data
    public static class PatVO {
        private Long id;
        private String name;
        /** 只展示前缀，永不返回完整令牌 */
        private String tokenPrefix;
        private List<String> scopes;
        private Instant expiresAt;
        private Boolean revoked;
        private Instant lastUsedAt;
        private Instant createdAt;

        static PatVO of(Pat p) {
            PatVO v = new PatVO();
            v.setId(p.getId());
            v.setName(p.getName());
            v.setTokenPrefix(p.getTokenPrefix());
            Set<String> scopes = PatService.parseScopes(p.getScopes());
            v.setScopes(List.copyOf(scopes));
            v.setExpiresAt(p.getExpiresAt());
            v.setRevoked(p.getRevoked());
            v.setLastUsedAt(p.getLastUsedAt());
            v.setCreatedAt(p.getCreatedAt());
            return v;
        }
    }

    public record IssuedVO(PatVO token, String plainText) {
    }
}
