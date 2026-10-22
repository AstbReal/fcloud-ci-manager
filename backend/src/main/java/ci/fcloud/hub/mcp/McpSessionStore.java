package ci.fcloud.hub.mcp;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ci.fcloud.hub.config.FcloudProperties;
import org.springframework.stereotype.Component;

/**
 * MCP 会话存储。
 *
 * <p>spec §14 明确单副本部署，因此内存 Map 足够；将来要多副本，
 * 换成集中式缓存实现即可，接口不变。</p>
 */
@Component
public class McpSessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public McpSessionStore(FcloudProperties props) {
        this.ttlMillis = Duration.ofMinutes(
                Math.max(1, props.getMcp().getSessionTtlMinutes())).toMillis();
    }

    public Session create(String protocolVersion) {
        sweep();
        Session s = new Session(UUID.randomUUID().toString(), protocolVersion,
                System.currentTimeMillis());
        sessions.put(s.id(), s);
        return s;
    }

    public Optional<Session> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Session s = sessions.get(id);
        if (s == null) {
            return Optional.empty();
        }
        if (expired(s)) {
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public void remove(String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    public int size() {
        return sessions.size();
    }

    /** 惰性清理，避免为一个纯内存缓存引入定时任务。 */
    private void sweep() {
        if (sessions.size() < 64) {
            return;
        }
        sessions.entrySet().removeIf(e -> expired(e.getValue()));
    }

    private boolean expired(Session s) {
        return System.currentTimeMillis() - s.createdAt() > ttlMillis;
    }

    public record Session(String id, String protocolVersion, long createdAt) {
    }
}
