package ci.fcloud.manager.setting;

import ci.fcloud.manager.common.CryptoService;
import ci.fcloud.manager.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 制品库配置（JFrog Artifactory V7 兼容接口）：用作备份的异地存放点。
 *
 * <p>内网的「Gitee 制品库」对外是 Artifactory 兼容协议，所以这里按 Artifactory 的
 * 术语建模：baseUrl + repoKey + dir。地址/仓库/目录明文，Token 用主密钥加密后落库，
 * 接口只回显尾 4 位。</p>
 */
@Service
@RequiredArgsConstructor
public class ArtifactRepoSettingService {

    public static final String KEY_ENABLED = "artifact.enabled";
    public static final String KEY_BASE_URL = "artifact.base_url";
    public static final String KEY_USERNAME = "artifact.username";
    public static final String KEY_REPO = "artifact.repo";
    public static final String KEY_DIR = "artifact.dir";
    public static final String KEY_TOKEN = "artifact.token";

    public static final String DEFAULT_DIR = "fcloud-backups";

    private final SystemSettingMapper mapper;
    private final CryptoService crypto;

    public record Config(boolean enabled, String baseUrl, String username, String repo,
                         String dir, String token) {

        /** 开了但缺 baseUrl/repo/token 一律视为不可用。 */
        public boolean usable() {
            return enabled && notBlank(baseUrl) && notBlank(repo) && notBlank(token);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    public Config load() {
        String base = readPlain(KEY_BASE_URL);
        return new Config(
                "true".equalsIgnoreCase(readPlain(KEY_ENABLED)),
                base == null || base.isBlank() ? null : trimTrailingSlash(base),
                readPlain(KEY_USERNAME),
                readPlain(KEY_REPO),
                normalizeDir(readPlain(KEY_DIR)),
                readSecret(KEY_TOKEN));
    }

    public boolean hasToken() {
        String t = readSecret(KEY_TOKEN);
        return t != null && !t.isBlank();
    }

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
            s = mapper.selectById(KEY_ENABLED);
        }
        return s == null ? null : s.getUpdatedAt();
    }

    @Transactional
    public void save(boolean enabled, String baseUrl, String username, String repo,
                     String dir, String token) {
        write(KEY_ENABLED, enabled ? "true" : "false", false);
        if (baseUrl != null) {
            String v = baseUrl.trim();
            write(KEY_BASE_URL, v.isEmpty() ? "" : trimTrailingSlash(v), false);
        }
        if (username != null) {
            write(KEY_USERNAME, username.trim(), false);
        }
        if (repo != null) {
            write(KEY_REPO, repo.trim().replaceAll("^/+|/+$", ""), false);
        }
        if (dir != null) {
            write(KEY_DIR, normalizeDir(dir), false);
        }
        // token 传空表示「不修改」，避免回显脱敏值再提交把真 token 覆盖掉
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

    private static String normalizeDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return DEFAULT_DIR;
        }
        String d = dir.trim().replaceAll("^/+|/+$", "");
        return d.isEmpty() ? DEFAULT_DIR : d;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
