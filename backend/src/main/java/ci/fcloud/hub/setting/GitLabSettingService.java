package ci.fcloud.hub.setting;

import ci.fcloud.hub.common.CryptoService;
import ci.fcloud.hub.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * GitLab 接入配置：地址明文、Token 用主密钥加密后落库，接口只回显尾 4 位。
 */
@Service
@RequiredArgsConstructor
public class GitLabSettingService {

    public static final String KEY_BASE_URL = "gitlab.base_url";
    public static final String KEY_TOKEN = "gitlab.token";
    public static final String DEFAULT_BASE_URL = "https://gitlab.com";

    private final SystemSettingMapper mapper;
    private final CryptoService crypto;

    public record Config(String baseUrl, String token) {
    }

    public Config load() {
        String base = readPlain(KEY_BASE_URL);
        String token = readSecret(KEY_TOKEN);
        return new Config(base == null || base.isBlank() ? DEFAULT_BASE_URL : base, token);
    }

    public boolean hasToken() {
        String t = readSecret(KEY_TOKEN);
        return t != null && !t.isBlank();
    }

    /** 只回显尾 4 位，避免泄露。 */
    public String tokenPreview() {
        String t = readSecret(KEY_TOKEN);
        if (t == null || t.isBlank()) {
            return null;
        }
        return t.length() <= 4 ? "****" : "••••" + t.substring(t.length() - 4);
    }

    public Instant updatedAt() {
        SystemSetting s = mapper.selectById(KEY_TOKEN);
        if (s == null) {
            s = mapper.selectById(KEY_BASE_URL);
        }
        return s == null ? null : s.getUpdatedAt();
    }

    @Transactional
    public void save(String baseUrl, String token) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            write(KEY_BASE_URL, trimTrailingSlash(baseUrl.trim()), false);
        }
        // token 传空表示"不修改"，避免前端回显脱敏值再提交把真 token 覆盖掉
        if (token != null && !token.isBlank()) {
            write(KEY_TOKEN, crypto.encrypt(token.trim()), true);
        }
    }

    public String readPlain(String key) {
        SystemSetting s = mapper.selectById(key);
        return s == null ? null : s.getSettingValue();
    }

    public String readSecret(String key) {
        SystemSetting s = mapper.selectById(key);
        if (s == null || s.getSettingValue() == null) {
            return null;
        }
        return Boolean.TRUE.equals(s.getEncrypted())
                ? crypto.decrypt(s.getSettingValue())
                : s.getSettingValue();
    }

    private void write(String key, String value, boolean encrypted) {
        SystemSetting s = mapper.selectById(key);
        boolean create = s == null;
        if (create) {
            s = new SystemSetting();
            s.setSettingKey(key);
        }
        s.setSettingValue(value);
        s.setEncrypted(encrypted);
        s.setUpdatedBy(SecurityUtils.currentUserId());
        s.setUpdatedAt(Instant.now());
        if (create) {
            mapper.insert(s);
        } else {
            mapper.updateById(s);
        }
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
