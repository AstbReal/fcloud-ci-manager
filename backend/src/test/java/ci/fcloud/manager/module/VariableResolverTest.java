package ci.fcloud.manager.module;

import ci.fcloud.manager.env.Environment;
import ci.fcloud.manager.env.EnvironmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * §2.2 变量解析优先级单测（核心算法，五种路径全覆盖）。
 *
 * 优先级：
 * 1. override(V, E, A)          应用级覆盖 · 当前环境
 * 2. override(V, E, null)       模块级覆盖 · 当前环境
 * 3. 沿 E 的父链逐级重复 1、2
 * 4. V.global_value
 * 5. 缺失 → 不输出
 */
class VariableResolverTest {

    /** dev → test → prod 继承链 */
    private static final long DEV = 1L;
    private static final long TEST = 2L;
    private static final long PROD = 3L;

    @Mock
    private VariableMapper variableMapper;
    @Mock
    private VariableOverrideMapper overrideMapper;
    @Mock
    private EnvironmentMapper environmentMapper;
    @Mock
    private ci.fcloud.manager.common.CryptoService cryptoService;

    @InjectMocks
    private VariableResolver resolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // dev.parent=null, test.parent=dev, prod.parent=test
        when(environmentMapper.selectById(DEV)).thenReturn(env(DEV, null));
        when(environmentMapper.selectById(TEST)).thenReturn(env(TEST, DEV));
        when(environmentMapper.selectById(PROD)).thenReturn(env(PROD, TEST));
        // 一次性解析上下文会把环境表整表读进来（不再逐层 selectById）
        when(environmentMapper.selectList(any())).thenReturn(
                List.of(env(DEV, null), env(TEST, DEV), env(PROD, TEST)));
    }

    private Environment env(long id, Long parent) {
        Environment e = new Environment();
        e.setId(id);
        e.setParentEnvId(parent);
        return e;
    }

    private Variable plainVar(Long id, String key, String global) {
        Variable v = new Variable();
        v.setId(id);
        v.setVarKey(key);
        v.setValueType("PLAIN");
        v.setGlobalValue(global);
        return v;
    }

    private VariableOverride appOverride(Long varId, Long envId, Long appId, String value) {
        VariableOverride o = new VariableOverride();
        o.setId(1000L + envId);
        o.setVariableId(varId);
        o.setEnvironmentId(envId);
        o.setApplicationId(appId);
        o.setValue(value);
        return o;
    }

    private VariableOverride moduleOverride(Long varId, Long envId, String value) {
        return appOverride(varId, envId, null, value);
    }

    private void stubOverrides(VariableOverride... all) {
        when(overrideMapper.selectList(any())).thenReturn(List.of(all));
    }

    // ---------------------------------------------------------------
    // 路径 1：应用级覆盖 · 当前环境 最高优先级
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.2-1 应用级覆盖·当前环境 优先于模块级覆盖与全局值")
    void appOverrideCurrentEnvWins() {
        Variable v = plainVar(10L, "DB_HOST", "global-host");
        stubOverrides(
                appOverride(10L, PROD, 100L, "app-prod-host"),
                moduleOverride(10L, PROD, "module-prod-host"));

        ResolvedValue r = resolver.resolveVariable(v, 100L, PROD, false);

        assertTrue(r.isFound());
        assertEquals("app-prod-host", r.getValue());
        assertEquals(ResolvedValue.Source.APP_OVERRIDE, r.getSource());
    }

    // ---------------------------------------------------------------
    // 路径 2：模块级覆盖 · 当前环境
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.2-2 无应用级覆盖时取模块级覆盖·当前环境")
    void moduleOverrideCurrentEnvWinsOverGlobal() {
        Variable v = plainVar(10L, "DB_HOST", "global-host");
        stubOverrides(moduleOverride(10L, PROD, "module-prod-host"));

        ResolvedValue r = resolver.resolveVariable(v, 100L, PROD, false);

        assertEquals("module-prod-host", r.getValue());
        assertEquals(ResolvedValue.Source.MODULE_OVERRIDE, r.getSource());
    }

    // ---------------------------------------------------------------
    // 路径 3：沿父链逐级重复 1、2
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.2-3a 当前环境无覆盖时沿父链找模块级覆盖（prod→test）")
    void fallsBackToParentEnvModuleOverride() {
        Variable v = plainVar(10L, "DB_HOST", "global-host");
        stubOverrides(moduleOverride(10L, TEST, "module-test-host"));

        ResolvedValue r = resolver.resolveVariable(v, 100L, PROD, false);

        assertEquals("module-test-host", r.getValue());
        assertEquals(ResolvedValue.Source.MODULE_OVERRIDE_ANCESTOR, r.getSource());
        assertEquals(TEST, r.getSourceEnvironmentId());
        assertTrue(r.isInherited());
    }

    @Test
    @DisplayName("§2.2-3b 同一环境内应用级覆盖优先于模块级覆盖（含父链环境）")
    void appOverrideBeatsModuleOverrideAtEachEnvLevel() {
        Variable v = plainVar(10L, "DB_HOST", "global-host");
        // dev 上两级都有：应取 dev 的应用级，而非 dev 的模块级
        stubOverrides(
                appOverride(10L, DEV, 100L, "app-dev-host"),
                moduleOverride(10L, DEV, "module-dev-host"));

        ResolvedValue r = resolver.resolveVariable(v, 100L, PROD, false);

        assertEquals("app-dev-host", r.getValue());
        assertEquals(ResolvedValue.Source.APP_OVERRIDE_ANCESTOR, r.getSource());
        assertEquals(DEV, r.getSourceEnvironmentId());
    }

    @Test
    @DisplayName("§2.2-3c 近环境优先于远环境（prod 无 → test 有 → dev 有）")
    void nearestAncestorWins() {
        Variable v = plainVar(10L, "DB_HOST", "global-host");
        stubOverrides(
                moduleOverride(10L, TEST, "module-test-host"),
                moduleOverride(10L, DEV, "module-dev-host"));

        ResolvedValue r = resolver.resolveVariable(v, 100L, PROD, false);

        assertEquals("module-test-host", r.getValue());
        assertEquals(TEST, r.getSourceEnvironmentId(), "test 比 dev 更近，应优先");
    }

    // ---------------------------------------------------------------
    // 路径 4：全局值
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.2-4 全链无覆盖时取全局值")
    void globalValueUsedWhenNoOverride() {
        Variable v = plainVar(10L, "DB_HOST", "global-host");
        stubOverrides(); // 空覆盖

        ResolvedValue r = resolver.resolveVariable(v, 100L, PROD, false);

        assertEquals("global-host", r.getValue());
        assertEquals(ResolvedValue.Source.GLOBAL, r.getSource());
    }

    // ---------------------------------------------------------------
    // 路径 5：缺失 → 不输出
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.2-5 无覆盖且无全局值 → found=false（该 key 不输出）")
    void missingWhenNoOverrideAndNoGlobal() {
        Variable v = plainVar(10L, "DB_HOST", null);
        stubOverrides();

        ResolvedValue r = resolver.resolveVariable(v, 100L, PROD, false);

        assertFalse(r.isFound());
        assertNull(r.getValue());
    }

    // ---------------------------------------------------------------
    // 私有模块：跳过第 1 步
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.2 私有模块（applicationId=null）跳过应用级覆盖")
    void privateModuleSkipsAppLevelOverride() {
        Variable v = plainVar(10L, "DB_HOST", "global-host");
        stubOverrides(
                appOverride(10L, PROD, 100L, "app-prod-host"),
                moduleOverride(10L, PROD, "module-prod-host"));

        ResolvedValue r = resolver.resolveVariable(v, null, PROD, false);

        assertEquals("module-prod-host", r.getValue());
        assertEquals(ResolvedValue.Source.MODULE_OVERRIDE, r.getSource());
    }

    @Test
    @DisplayName("§2.2 应用级覆盖只对所属应用生效，其他应用看不到")
    void appOverrideScopedToOwningApplication() {
        Variable v = plainVar(10L, "DB_HOST", "global-host");
        // 应用 100 的覆盖；应用 200 请求时应回退到模块级/全局
        stubOverrides(appOverride(10L, PROD, 100L, "app100-host"));

        ResolvedValue r = resolver.resolveVariable(v, 200L, PROD, false);

        assertEquals("global-host", r.getValue(),
                "应用 200 不应看到应用 100 的覆盖");
        assertEquals(ResolvedValue.Source.GLOBAL, r.getSource());
    }

    // ---------------------------------------------------------------
    // 防环
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.2 父链成环时不死循环，按已访问顺序终止")
    void parentChainCycleTerminates() {
        // A.parent=B, B.parent=A 成环
        when(environmentMapper.selectById(50L)).thenReturn(env(50L, 60L));
        when(environmentMapper.selectById(60L)).thenReturn(env(60L, 50L));

        Variable v = plainVar(10L, "DB_HOST", "global-host");
        stubOverrides();

        ResolvedValue r = resolver.resolveVariable(v, 100L, 50L, false);

        // 成环但无覆盖，最终回退全局值，不死循环
        assertEquals("global-host", r.getValue());
        assertEquals(ResolvedValue.Source.GLOBAL, r.getSource());
    }

    // ---------------------------------------------------------------
    // 加密变量
    // ---------------------------------------------------------------

    @Test
    @DisplayName("§2.2 加密变量 revealPlain=false → 脱敏；true → 解密明文")
    void encryptedVariableMasking() {
        Variable v = plainVar(10L, "DB_PASSWORD", "CIPHER");
        v.setValueType("ENCRYPTED");
        stubOverrides();
        when(cryptoService.decrypt("CIPHER")).thenReturn("p@ssw0rd");

        ResolvedValue masked = resolver.resolveVariable(v, 100L, PROD, false);
        assertTrue(masked.isMasked());
        assertEquals(VariableResolver.MASKED_PLACEHOLDER, masked.getValue());

        ResolvedValue plain = resolver.resolveVariable(v, 100L, PROD, true);
        assertFalse(plain.isMasked());
        assertEquals("p@ssw0rd", plain.getValue());
    }

    @Test
    @DisplayName("§2.2 加密变量的覆盖值同样按权限脱敏/解密")
    void encryptedOverrideMasking() {
        Variable v = plainVar(10L, "DB_PASSWORD", null);
        v.setValueType("ENCRYPTED");
        stubOverrides(appOverride(10L, PROD, 100L, "CIPHER-OVERRIDE"));
        when(cryptoService.decrypt("CIPHER-OVERRIDE")).thenReturn("override-secret");

        ResolvedValue masked = resolver.resolveVariable(v, 100L, PROD, false);
        assertTrue(masked.isMasked());

        ResolvedValue plain = resolver.resolveVariable(v, 100L, PROD, true);
        assertEquals("override-secret", plain.getValue());
        assertEquals(ResolvedValue.Source.APP_OVERRIDE, plain.getSource());
    }

    @Test
    @DisplayName("§2.2 变量为 null → found=false")
    void nullVariableNotFound() {
        assertFalse(resolver.resolveVariable(null, 100L, PROD, false).isFound());
    }
}
