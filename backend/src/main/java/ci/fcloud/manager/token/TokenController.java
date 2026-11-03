package ci.fcloud.manager.token;

import ci.fcloud.manager.audit.AuditService;
import ci.fcloud.manager.common.ApiResponse;
import ci.fcloud.manager.common.BizException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * §5.9 Token 签发/吊销（仅系统管理员，路径前缀 /api/console/admin）。
 */
@RestController
@RequestMapping("/api/console/admin")
@RequiredArgsConstructor
public class TokenController {

    private final ApiTokenService tokenService;
    private final AuditService auditService;

    /** 全局 Token 列表（不区分应用）。 */
    @GetMapping("/tokens")
    public ApiResponse<List<TokenVO>> list() {
        return ApiResponse.ok(tokenService.listAll().stream()
                .map(TokenVO::of).toList());
    }

    /**
     * §5.9 签发：返回明文一次（之后只能看到 prefix）。
     */
    @PostMapping("/tokens")
    public ApiResponse<IssuedVO> create(@RequestBody CreateBody body) {
        ApiTokenService.Issued issued = tokenService.issue(body.getName(),
                body.getExpiresAt());
        auditService.log("TOKEN_ISSUE", "TOKEN", String.valueOf(issued.token().getId()),
                false, auditService.detailJson(
                        "prefix", issued.token().getTokenPrefix()));
        return ApiResponse.ok(new IssuedVO(TokenVO.of(issued.token()), issued.plainText()));
    }

    /**
     * §5.9 吊销（高危）。
     */
    @DeleteMapping("/tokens/{id}")
    public ApiResponse<Void> revoke(@PathVariable Long id) {
        ApiToken t = tokenService.getById(id);
        if (t == null) {
            throw BizException.notFound("Token 不存在: " + id);
        }
        String prefix = t.getTokenPrefix();
        tokenService.revoke(id);
        auditService.log("TOKEN_REVOKE", "TOKEN", String.valueOf(id), true,
                auditService.detailJson("prefix", prefix));
        return ApiResponse.ok();
    }

    @Data
    public static class CreateBody {
        private String name;
        private Instant expiresAt;
    }

    @Data
    public static class TokenVO {
        private Long id;
        private String name;
        /** 只展示前缀，永不返回完整 Token */
        private String tokenPrefix;
        private Instant expiresAt;
        private Boolean revoked;
        private Instant lastUsedAt;
        private Instant createdAt;

        static TokenVO of(ApiToken t) {
            TokenVO v = new TokenVO();
            v.setId(t.getId());
            v.setName(t.getName());
            v.setTokenPrefix(t.getTokenPrefix());
            v.setExpiresAt(t.getExpiresAt());
            v.setRevoked(t.getRevoked());
            v.setLastUsedAt(t.getLastUsedAt());
            v.setCreatedAt(t.getCreatedAt());
            return v;
        }
    }

    public record IssuedVO(TokenVO token, String plainText) {
    }
}
