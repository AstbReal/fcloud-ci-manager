package ci.fcloud.manager.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * §6.5 加密主密钥来源：
 * FCLOUD_MASTER_KEY（Base64 32 字节）→ master-key-file → 自动生成并落盘。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "fcloud")
public class FcloudProperties {

    private Gitlab gitlab = new Gitlab();
    private Oauth oauth = new Oauth();
    private Jwt jwt = new Jwt();
    private Cookie cookie = new Cookie();
    private String masterKey;
    private String masterKeyFile = "/data/config-hub/master.key";
    private Storage storage = new Storage();
    private Backup backup = new Backup();
    private Audit audit = new Audit();
    private AccessLog accessLog = new AccessLog();
    private RateLimit rateLimit = new RateLimit();
    private Mcp mcp = new Mcp();

    @Getter
    @Setter
    public static class Gitlab {
        private String baseUrl = "https://gitlab.com";
    }

    @Getter
    @Setter
    public static class Oauth {
        private String redirectUri;
    }

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long ttlHours = 24;
        private long rememberTtlDays = 7;
    }

    @Getter
    @Setter
    public static class Cookie {
        private boolean secure = false;
    }

    @Getter
    @Setter
    public static class Storage {
        private String root = "/data/config-hub/storage";
        private long maxFileSizeMb = 50;
        private long maxModuleTotalMb = 500;
        private int keepVersions = 3;
    }

    @Getter
    @Setter
    public static class Backup {
        private String dir = "/data/config-hub/backups";
        private int retain = 3;
    }

    @Getter
    @Setter
    public static class Audit {
        private long retainDays = 7;
    }

    @Getter
    @Setter
    public static class AccessLog {
        private long retainDays = 30;
    }

    @Getter
    @Setter
    public static class RateLimit {
        private long perMinute = 60;
    }

    /**
     * MCP Server（§design/mcp-server-design.md）。
     *
     * <p>三个开关都默认保守：写关闭、明文关闭；只读能力默认开放。</p>
     */
    @Getter
    @Setter
    public static class Mcp {
        /** 全局开关；false 时 /mcp 直接 404 */
        private boolean enabled = true;
        /** 写工具开关；false 时写工具不出现在 tools/list 中 */
        private boolean writeEnabled = false;
        /** 加密变量明文开关；false 时 reveal_secret 不可用 */
        private boolean revealEnabled = false;
        /** 单次工具结果上限（字节），超出截断 */
        private int maxResultBytes = 32768;
        /** 会话 TTL（分钟） */
        private long sessionTtlMinutes = 30;
        /** 每个 Token 每分钟调用上限 */
        private long ratePerMinute = 120;
    }
}
