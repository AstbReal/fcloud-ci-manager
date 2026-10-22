package ci.fcloud.hub.token;

import ci.fcloud.hub.common.BizException;
import ci.fcloud.hub.common.LocalCache;
import ci.fcloud.hub.common.SecurityUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * §4.1 流水线 API Token。
 *
 * - Token 创建时完整展示一次；库中只存 hex(sha256(token)) 与前缀
 * - 本地缓存校验结果（TTL 5 分钟），吊销时同步删除缓存
 * - Token 作用域 = 全局（不区分应用、不区分环境），可访问所有应用
 */
@Service
@RequiredArgsConstructor
public class ApiTokenService extends ServiceImpl<ApiTokenMapper, ApiToken> {

    /** Token 明文前缀，便于识别与脱敏（如 chub_ab12） */
    public static final String TOKEN_PREFIX = "chub_";
    private static final int TOKEN_BYTES = 24;
    private static final String CACHE_PREFIX = "chub:token:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final LocalCache cache;
    private final SecureRandom random = new SecureRandom();

    /**
     * 生成新 Token。返回 {实体, 明文}；明文只在此处返回一次。
     */
    public Issued issue(String name, Instant expiresAt) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String plain = TOKEN_PREFIX + HexFormat.of().formatHex(raw);

        ApiToken t = new ApiToken();
        t.setName(name == null || name.isBlank() ? "default" : name);
        t.setTokenHash(sha256(plain));
        t.setTokenPrefix(prefixOf(plain));
        t.setExpiresAt(expiresAt);
        t.setRevoked(false);
        t.setCreatedBy(SecurityUtils.currentUserId());
        save(t);
        return new Issued(t, plain);
    }

    /**
     * §4.1 吊销：置 revoked=true，并同步删除本地缓存。
     */
    public void revoke(Long id) {
        ApiToken t = getById(id);
        if (t == null) {
            throw BizException.notFound("Token 不存在: " + id);
        }
        t.setRevoked(true);
        updateById(t);
        cache.delete(CACHE_PREFIX + t.getTokenHash());
    }

    public List<ApiToken> listAll() {
        return lambdaQuery().orderByDesc(ApiToken::getCreatedAt).list();
    }

    /**
     * §4.1 校验 Token。命中缓存直接返回；否则查库并缓存 5 分钟。
     * 缺失 / 无效 / 过期 / 吊销 → empty。
     */
    public Optional<ApiToken> verify(String plain) {
        if (plain == null || plain.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256(plain);

        // 1) 本地缓存：value = tokenId
        String cached = cache.get(CACHE_PREFIX + hash);
        if (cached != null) {
            ApiToken t = getById(Long.parseLong(cached.trim()));
            if (t != null && !Boolean.TRUE.equals(t.getRevoked())) {
                return Optional.of(t);
            }
            cache.delete(CACHE_PREFIX + hash);
            return Optional.empty();
        }

        // 2) 查库
        ApiToken t = lambdaQuery().eq(ApiToken::getTokenHash, hash).one();
        if (t == null) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(t.getRevoked())) {
            return Optional.empty();
        }
        if (t.getExpiresAt() != null && t.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        cache.set(CACHE_PREFIX + hash,
                String.valueOf(t.getId()), CACHE_TTL);
        return Optional.of(t);
    }

    /**
     * 更新 last_used_at（异步/低频，避免每次请求都写库）。
     */
    public void touch(Long id) {
        lambdaUpdate().eq(ApiToken::getId, id)
                .set(ApiToken::getLastUsedAt, Instant.now()).update();
    }

    /**
     * §9.2 恢复后清理 Token 缓存。
     */
    public void clearCache() {
        cache.deleteByPrefix(CACHE_PREFIX);
    }

    // ---------------- 工具 ----------------

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 unavailable");
        }
    }

    /** 展示用前缀：chub_ + 前 8 位 */
    public static String prefixOf(String plain) {
        return plain.substring(0, Math.min(plain.length(), TOKEN_PREFIX.length() + 8));
    }

    /**
     * 签发结果：实体 + 明文（仅一次）。
     */
    public record Issued(ApiToken token, String plainText) {
    }
}
