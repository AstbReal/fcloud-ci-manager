package ci.fcloud.manager.pat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import ci.fcloud.manager.common.BizException;
import ci.fcloud.manager.common.LocalCache;
import ci.fcloud.manager.common.CurrentUser;
import ci.fcloud.manager.common.SecurityUtils;
import ci.fcloud.manager.user.GlobalRole;
import ci.fcloud.manager.user.User;
import ci.fcloud.manager.user.UserMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * MCP 个人访问令牌的签发与校验。
 *
 * <p>与 {@link ci.fcloud.manager.token.ApiTokenService} 结构同构（只存 SHA-256 +
 * 本地缓存 5 分钟 + 吊销删缓存），但多两件事：绑定用户、带 scope。</p>
 */
@Service
@RequiredArgsConstructor
public class PatService extends ServiceImpl<PatMapper, Pat> {

    /** 明文前缀，与流水线 Token（chub_）区分，便于在日志/配置里一眼识别 */
    public static final String TOKEN_PREFIX = "chub_pat_";

    public static final String SCOPE_READ = "mcp:read";
    public static final String SCOPE_WRITE = "mcp:write";
    public static final String SCOPE_SECRET = "mcp:secret";

    private static final List<String> SCOPE_ORDER = List.of(SCOPE_READ, SCOPE_WRITE, SCOPE_SECRET);
    private static final int TOKEN_BYTES = 24;
    private static final String CACHE_PREFIX = "chub:pat:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final LocalCache cache;
    private final UserMapper userMapper;
    private final SecureRandom random = new SecureRandom();

    /**
     * 签发。明文只在此处返回一次。
     */
    public Issued issue(String name, Collection<String> scopes, Instant expiresAt) {
        Long uid = SecurityUtils.currentUserId();
        if (uid == null) {
            throw BizException.forbidden("未登录，无法签发访问令牌");
        }
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String plain = TOKEN_PREFIX + HexFormat.of().formatHex(raw);

        Pat p = new Pat();
        p.setUserId(uid);
        p.setName(name == null || name.isBlank() ? "mcp-client" : name.trim());
        p.setTokenHash(sha256(plain));
        p.setTokenPrefix(prefixOf(plain));
        p.setScopes(normalizeScopes(scopes));
        p.setExpiresAt(expiresAt);
        p.setRevoked(false);
        save(p);
        return new Issued(p, plain);
    }

    /**
     * 清空全部 PAT 缓存。恢复备份后调用：包里的令牌与当前库可能完全不同，
     * 不清会让旧 token 在 TTL 内继续解析出主体。
     */
    public void clearCache() {
        cache.deleteByPrefix(CACHE_PREFIX);
    }

    /**
     * 当前用户自己的令牌（管理员也只看自己的，避免越权猜测他人凭证）。
     */
    public List<Pat> listMine() {
        Long uid = SecurityUtils.currentUserId();
        if (uid == null) {
            throw BizException.forbidden("未登录");
        }
        return lambdaQuery().eq(Pat::getUserId, uid)
                .orderByDesc(Pat::getCreatedAt)
                .list();
    }

    /**
     * 吊销。只能吊销自己的令牌；同时清本地缓存。
     */
    public void revoke(Long id) {
        Pat p = getById(id);
        if (p == null) {
            throw BizException.notFound("令牌不存在: " + id);
        }
        if (!Objects.equals(p.getUserId(), SecurityUtils.currentUserId())) {
            throw BizException.forbidden("只能吊销自己的令牌");
        }
        p.setRevoked(true);
        updateById(p);
        cache.delete(CACHE_PREFIX + p.getTokenHash());
    }

    /**
     * 校验并解析主体。缺失 / 无效 / 过期 / 吊销 / 用户已删 → empty。
     */
    public Optional<Verified> verify(String plain) {
        if (plain == null || plain.isBlank() || !plain.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String hash = sha256(plain);

        String cached = cache.get(CACHE_PREFIX + hash);
        Pat p;
        if (cached != null) {
            p = getById(Long.parseLong(cached.trim()));
            if (p == null || Boolean.TRUE.equals(p.getRevoked())) {
                cache.delete(CACHE_PREFIX + hash);
                return Optional.empty();
            }
        } else {
            p = lambdaQuery().eq(Pat::getTokenHash, hash).one();
            if (p == null) {
                return Optional.empty();
            }
            if (Boolean.TRUE.equals(p.getRevoked())) {
                return Optional.empty();
            }
            if (p.getExpiresAt() != null && p.getExpiresAt().isBefore(Instant.now())) {
                return Optional.empty();
            }
            cache.set(CACHE_PREFIX + hash, String.valueOf(p.getId()), CACHE_TTL);
        }

        User u = userMapper.selectById(p.getUserId());
        if (u == null) {
            return Optional.empty();
        }
        CurrentUser cu = new CurrentUser(
                u.getId(), u.getEmail(), u.getName(), roleOf(u.getGlobalRole()),
                u.getNickname(), u.getAvatarUrl());
        return Optional.of(new Verified(p, cu, parseScopes(p.getScopes())));
    }

    public void touch(Long id) {
        lambdaUpdate().eq(Pat::getId, id).set(Pat::getLastUsedAt, Instant.now()).update();
    }

    // ---------------- 工具 ----------------

    /**
     * scope 规范化：只接受白名单，且 mcp:read 永远保留（其余能力都建立在能读之上）。
     */
    public static String normalizeScopes(Collection<String> raw) {
        Set<String> picked = new LinkedHashSet<>();
        if (raw != null) {
            for (String s : raw) {
                if (s != null && SCOPE_ORDER.contains(s.trim())) {
                    picked.add(s.trim());
                }
            }
        }
        picked.add(SCOPE_READ);
        return SCOPE_ORDER.stream().filter(picked::contains).reduce((a, b) -> a + "," + b)
                .orElse(SCOPE_READ);
    }

    public static Set<String> parseScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return Set.of(SCOPE_READ);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : scopes.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        out.add(SCOPE_READ);
        return out;
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 unavailable");
        }
    }

    public static String prefixOf(String plain) {
        return plain.substring(0, Math.min(plain.length(), TOKEN_PREFIX.length() + 8));
    }

    private static GlobalRole roleOf(String raw) {
        return "SYSTEM_ADMIN".equalsIgnoreCase(raw) ? GlobalRole.SYSTEM_ADMIN : GlobalRole.NONE;
    }

    public record Issued(Pat pat, String plainText) {
    }

    public record Verified(Pat pat, CurrentUser user, Set<String> scopes) {
    }
}
