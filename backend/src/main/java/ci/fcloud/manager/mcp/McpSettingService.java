package ci.fcloud.manager.mcp;

import java.time.Instant;

import ci.fcloud.manager.config.FcloudProperties;
import ci.fcloud.manager.setting.SystemSetting;
import ci.fcloud.manager.setting.SystemSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 运行时开关。
 *
 * <p>优先级：数据库（页面可改，即时生效） &gt; 配置文件/环境变量（部署默认值）。
 * 没写过 DB 时回落到 {@link FcloudProperties}，所以老部署行为不变。</p>
 *
 * <p>本地缓存 5 秒：工具调用频率不高，但每个 tools/call 都查三次库纯属浪费。</p>
 */
@Service
@RequiredArgsConstructor
public class McpSettingService {

    public static final String KEY_ENABLED = "mcp.enabled";
    public static final String KEY_WRITE = "mcp.write_enabled";
    public static final String KEY_REVEAL = "mcp.reveal_enabled";

    private static final long CACHE_MS = 5_000L;

    private final SystemSettingMapper mapper;
    private final FcloudProperties props;

    private volatile Snapshot cached;
    private volatile long cachedAt;

    public boolean enabled() {
        return snapshot().enabled();
    }

    public boolean writeEnabled() {
        return snapshot().writeEnabled();
    }

    public boolean revealEnabled() {
        return snapshot().revealEnabled();
    }

    public Snapshot snapshot() {
        Snapshot c = cached;
        if (c != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
            return c;
        }
        Snapshot fresh = new Snapshot(
                bool(KEY_ENABLED, props.getMcp().isEnabled()),
                bool(KEY_WRITE, props.getMcp().isWriteEnabled()),
                bool(KEY_REVEAL, props.getMcp().isRevealEnabled()));
        cached = fresh;
        cachedAt = System.currentTimeMillis();
        return fresh;
    }

    /** 三个开关任意一个为 null 表示不修改。 */
    @Transactional
    public void save(Boolean enabled, Boolean write, Boolean reveal, Long operatorId) {
        if (enabled != null) {
            write(KEY_ENABLED, enabled, operatorId);
        }
        if (write != null) {
            write(KEY_WRITE, write, operatorId);
        }
        if (reveal != null) {
            write(KEY_REVEAL, reveal, operatorId);
        }
        invalidate();
    }

    /** 最近一次修改时间，取三个 key 的最大值。 */
    public Instant updatedAt() {
        Instant latest = null;
        for (String key : new String[]{KEY_ENABLED, KEY_WRITE, KEY_REVEAL}) {
            SystemSetting s = mapper.selectById(key);
            if (s != null && s.getUpdatedAt() != null
                    && (latest == null || s.getUpdatedAt().isAfter(latest))) {
                latest = s.getUpdatedAt();
            }
        }
        return latest;
    }

    public boolean persisted() {
        return mapper.selectById(KEY_ENABLED) != null
                || mapper.selectById(KEY_WRITE) != null
                || mapper.selectById(KEY_REVEAL) != null;
    }

    private void invalidate() {
        cached = null;
        cachedAt = 0L;
    }

    private boolean bool(String key, boolean fallback) {
        SystemSetting s = mapper.selectById(key);
        if (s == null || s.getSettingValue() == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(s.getSettingValue().trim());
    }

    private void write(String key, boolean value, Long operatorId) {
        SystemSetting s = mapper.selectById(key);
        boolean create = s == null;
        if (create) {
            s = new SystemSetting();
            s.setSettingKey(key);
        }
        s.setSettingValue(String.valueOf(value));
        s.setEncrypted(false);
        s.setUpdatedBy(operatorId);
        s.setUpdatedAt(Instant.now());
        if (create) {
            mapper.insert(s);
        } else {
            mapper.updateById(s);
        }
    }

    public record Snapshot(boolean enabled, boolean writeEnabled, boolean revealEnabled) {
    }
}
