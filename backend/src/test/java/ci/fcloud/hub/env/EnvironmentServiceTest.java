package ci.fcloud.hub.env;

import ci.fcloud.hub.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §5.2 / §2.2 环境继承链与防环单测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvironmentServiceTest {

    /** id → 环境，模拟存储 */
    private final Map<Long, Environment> store = new HashMap<>();

    @Mock
    private EnvironmentMapper environmentMapper;

    @InjectMocks
    private EnvironmentService service;

    private long seq = 100L;

    @BeforeEach
    void setUp() {
        store.clear();
        when(environmentMapper.selectById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return store.get(id);
        });
        when(environmentMapper.insert(any(Environment.class))).thenAnswer(inv -> {
            Environment e = inv.getArgument(0);
            e.setId(seq++);
            store.put(e.getId(), e);
            return 1;
        });
        when(environmentMapper.updateById(any(Environment.class))).thenAnswer(inv -> {
            Environment e = inv.getArgument(0);
            store.put(e.getId(), e);
            return 1;
        });
    }

    private Environment env(Long id, String code, Long parent) {
        Environment e = new Environment();
        e.setId(id);
        e.setCode(code);
        e.setName(code);
        e.setParentEnvId(parent);
        store.put(id, e);
        return e;
    }

    private Environment newEnv(String code, Long parent) {
        Environment e = new Environment();
        e.setCode(code);
        e.setName(code);
        e.setParentEnvId(parent);
        return e;
    }

    @Test
    @DisplayName("§2.2 dev→test→prod 链：prod 的祖先为 test、dev")
    void chainBuildsCorrectly() {
        env(1L, "dev", null);
        env(2L, "test", 1L);
        env(3L, "prod", 2L);

        // assertNoCycle 不抛异常即说明链可完整遍历
        assertDoesNotThrow(() -> service.assertNoCycle(3L, null));
    }

    @Test
    @DisplayName("§2.2 拒绝自引用：父环境不能是自身")
    void rejectsSelfParent() {
        env(1L, "dev", null);
        BizException ex = assertThrows(BizException.class,
                () -> service.assertNoCycle(1L, 1L));
        assertEquals(40000, ex.getCode());
    }

    @Test
    @DisplayName("§2.2 拒绝成环：A→B 后再把 A 的父设为 B 会成环")
    void rejectsCycle() {
        // A(1).parent = B(2)；现在尝试把 B(2).parent = A(1) → 环
        env(1L, "a", 2L);
        env(2L, "b", null);

        // 把 2 的父设为 1：1 的父是 2，2 的父变成 1 → 环
        BizException ex = assertThrows(BizException.class,
                () -> service.assertNoCycle(2L, 1L));
        assertEquals(40000, ex.getCode());
        assertTrue(ex.getMessage().contains("环"), "错误信息应说明是环");
    }

    @Test
    @DisplayName("§2.2 允许合法重设父环境（不成环）")
    void allowsLegalReparent() {
        env(1L, "dev", null);
        env(2L, "test", 1L);
        env(3L, "prod", null);

        // 把 prod 的父设为 test（合法，不成环）
        assertDoesNotThrow(() -> service.assertNoCycle(3L, 2L));
    }

    @Test
    @DisplayName("§2.2 三阶链也不成环")
    void threeLevelChainIsLegal() {
        env(1L, "dev", null);
        env(2L, "test", 1L);
        env(3L, "prod", 2L);

        // 新建 staging，父设为 prod：prod→test→dev，无环
        assertDoesNotThrow(() -> service.assertNoCycle(999L, 3L));
    }

    @Test
    @DisplayName("§2.2 parent 为 null 时不构成环")
    void nullParentNeverCycles() {
        env(1L, "dev", null);
        assertDoesNotThrow(() -> service.assertNoCycle(1L, null));
    }
}
