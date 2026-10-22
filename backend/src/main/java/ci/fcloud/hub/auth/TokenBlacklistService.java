package ci.fcloud.hub.auth;

import ci.fcloud.hub.common.CurrentUser;
import ci.fcloud.hub.common.LocalCache;
import ci.fcloud.hub.user.GlobalRole;
import ci.fcloud.hub.user.User;
import ci.fcloud.hub.user.UserService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * §6.2 注销：黑名单 key=jwt:blacklist:{jti}，TTL=剩余有效期。
 * 存在进程内（LocalCache），重启即失效——见 LocalCache 的说明。
 */
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String PREFIX = "jwt:blacklist:";

    private final LocalCache cache;
    private final JwtService jwtService;
    private final UserService userService;

    public void blacklist(String jti, long ttlSeconds) {
        if (jti == null || ttlSeconds <= 0) {
            return;
        }
        cache.set(PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        return cache.get(PREFIX + jti) != null;
    }

    /**
     * 解析 token 并装载当前用户；无效 / 已吊销返回 null。
     * 注意：用户角色以 JWT 为准（登录时写入），若库中角色被改，下次登录生效。
     */
    public CurrentUser authenticate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Claims claims = jwtService.parse(token);
        if (claims == null) {
            return null;
        }
        if (isBlacklisted(claims.getId())) {
            return null;
        }
        Long uid = claims.get("uid", Long.class);
        if (uid == null) {
            return null;
        }
        User u = userService.getById(uid);
        if (u == null) {
            return null;
        }
        GlobalRole role = GlobalRole.NONE;
        try {
            role = GlobalRole.valueOf(u.getGlobalRole());
        } catch (IllegalArgumentException ignored) {
            // 未知角色按 NONE 处理
        }
        return new CurrentUser(u.getId(), u.getEmail(), u.getName(), role,
                u.getNickname(), u.getAvatarUrl());
    }
}
