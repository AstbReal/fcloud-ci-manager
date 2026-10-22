package ci.fcloud.hub.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * §6.2 JWT（HS256）签发/解析。claims：sub=email、uid、name、role、jti、exp。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlHours;
    private final long rememberTtlDays;

    public JwtService(ci.fcloud.hub.config.FcloudProperties props) {
        String secret = props.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            // 未配置时给一个本地开发可用的默认值；生产必须显式提供（§11.3 标记必填）
            secret = "fcloud-ci-hub-local-dev-secret-please-override";
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlHours = props.getJwt().getTtlHours();
        this.rememberTtlDays = props.getJwt().getRememberTtlDays();
    }

    public String issue(Long uid, String email, String name, String role, boolean remember) {
        Instant now = Instant.now();
        Instant exp = remember
                ? now.plus(Duration.ofDays(rememberTtlDays))
                : now.plus(Duration.ofHours(ttlHours));
        return Jwts.builder()
                .setSubject(email)
                .claim("uid", uid)
                .claim("name", name)
                .claim("role", role)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public long ttlSeconds(boolean remember) {
        return remember ? rememberTtlDays * 86400 : ttlHours * 3600;
    }

    /** 解析成功返回 claims；失败（过期/篡改）返回 null。 */
    public Claims parse(String token) {
        try {
            Jws<Claims> jws = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return jws.getBody();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public String jti(Claims claims) {
        return claims.getId();
    }

    /** 剩余有效期（秒），用于注销黑名单 TTL（§6.2）。 */
    public long remainingSeconds(Claims claims) {
        Instant exp = claims.getExpiration().toInstant();
        long s = Duration.between(Instant.now(), exp).getSeconds();
        return Math.max(s, 0);
    }
}
