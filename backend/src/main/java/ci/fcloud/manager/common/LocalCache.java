package ci.fcloud.manager.common;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内缓存，替代原先依赖 Redis 的三类状态：
 * <ul>
 *   <li>JWT 注销黑名单（TTL = 剩余有效期）</li>
 *   <li>流水线 Token / PAT 的 hash → id 缓存（TTL 5 分钟）</li>
 *   <li>接口限流的固定窗口计数（TTL 120 秒）</li>
 * </ul>
 *
 * <p>这些数据都是「可以重建」的：进程重启后黑名单丢失（旧 JWT 在有效期内可再用）、
 * 缓存失效只是多查一次库、限流从零开始。单实例部署（本项目的单镜像形态）下语义等同 Redis。</p>
 *
 * <p><b>多副本部署不适用</b>：各副本的黑名单/计数互不可见。要横向扩容必须换回集中式缓存，
 * 见 README 决策 D26。</p>
 */
@Component
public class LocalCache {

    /** 过期条目每积累这么多次写入清扫一遍，避免只写不读的 key 永久占内存 */
    private static final long SWEEP_EVERY = 2048;

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final AtomicLong writes = new AtomicLong();

    /** 未过期则返回 value，否则返回 null */
    public String get(String key) {
        Entry e = map.get(key);
        if (e == null) {
            return null;
        }
        if (e.expired()) {
            map.remove(key, e);
            return null;
        }
        return e.value;
    }

    public void set(String key, String value, Duration ttl) {
        long expireAt = (ttl == null || ttl.isZero() || ttl.isNegative())
                ? 0L
                : System.currentTimeMillis() + ttl.toMillis();
        map.put(key, new Entry(value, expireAt));
        sweepIfNeeded();
    }

    public void delete(String key) {
        map.remove(key);
    }

    public void deleteByPrefix(String prefix) {
        map.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * 固定窗口计数：不存在或已过期则从 1 开始并重设 TTL，否则原子自增。
     * 返回自增后的值。
     */
    public long increment(String key, Duration ttl) {
        long now = System.currentTimeMillis();
        Entry e = map.compute(key, (k, old) -> {
            if (old == null || old.expired()) {
                return new Entry("1", now + ttl.toMillis());
            }
            return new Entry(Long.toString(old.count() + 1), old.expireAt);
        });
        sweepIfNeeded();
        return e.count();
    }

    /**
     * 把 TTL 续到指定值（对应 Redis 的 expire），不改变当前值。
     * 计数场景下 {@link #increment} 已经设过 TTL，这里只用于兜底。
     */
    public void expire(String key, Duration ttl) {
        long expireAt = System.currentTimeMillis() + ttl.toMillis();
        map.computeIfPresent(key, (k, old) -> new Entry(old.value, expireAt));
    }

    private void sweepIfNeeded() {
        if (writes.incrementAndGet() % SWEEP_EVERY != 0) {
            return;
        }
        map.entrySet().removeIf(en -> en.getValue().expired());
    }

    private static final class Entry {
        private final String value;
        /** 0 表示永不过期 */
        private final long expireAt;

        private Entry(String value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

        private boolean expired() {
            return expireAt > 0 && expireAt <= System.currentTimeMillis();
        }

        private long count() {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return 1L;
            }
        }
    }
}
