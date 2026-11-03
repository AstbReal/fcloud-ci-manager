package ci.fcloud.manager.module;

import ci.fcloud.manager.common.CryptoService;
import ci.fcloud.manager.config.FcloudProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §4.2 dotenv 序列化/解析单测。
 */
class DotenvCodecTest {

    private final DotenvCodec codec = new DotenvCodec();
    private final CryptoService crypto = new CryptoService(propsWithKey());

    private static FcloudProperties propsWithKey() {
        FcloudProperties p = new FcloudProperties();
        p.setMasterKey(Base64.getEncoder().encodeToString(new byte[32]));
        return p;
    }

    // ---------------- 转义（§4.2） ----------------

    @Test
    @DisplayName("§4.2 普通值不加引号")
    void plainValueNotQuoted() {
        assertEquals("10.0.2.9", codec.escape("10.0.2.9"));
    }

    @Test
    @DisplayName("§4.2 含空格用单引号包裹")
    void spaceIsQuoted() {
        assertEquals("'p@ss word'", codec.escape("p@ss word"));
    }

    @Test
    @DisplayName("§4.2 含 # 用单引号包裹")
    void hashIsQuoted() {
        assertEquals("'a#b'", codec.escape("a#b"));
    }

    @Test
    @DisplayName("§4.2 含 $ 用单引号包裹")
    void dollarIsQuoted() {
        assertEquals("'$HOME'", codec.escape("$HOME"));
    }

    @Test
    @DisplayName("§4.2 含换行用单引号包裹")
    void newlineIsQuoted() {
        assertEquals("'a\nb'", codec.escape("a\nb"));
    }

    @Test
    @DisplayName("§4.2 内部单引号转 '\\''")
    void innerSingleQuoteEscaped() {
        assertEquals("'it'\\''s'", codec.escape("it's"));
    }

    @Test
    @DisplayName("§4.2 https URL 含 $ 时被引号保护")
    void urlWithDollarQuoted() {
        assertEquals("'https://x/$y'", codec.escape("https://x/$y"));
    }

    // ---------------- 渲染（§4.2） ----------------

    @Test
    @DisplayName("§4.2 每模块前输出 '# module: {code}' 分组注释")
    void rendersModuleHeaders() {
        String out = codec.render(List.of(
                new DotenvCodec.ModuleEntries("db-config", List.of(
                        new DotenvCodec.Entry("DB_HOST", "10.0.2.9", false),
                        new DotenvCodec.Entry("DB_PORT", "5432", false))),
                new DotenvCodec.ModuleEntries("common-notify", List.of(
                        new DotenvCodec.Entry("NOTIFY_URL", "https://im.internal/hook", false)))
        ), true, crypto);

        String[] lines = out.split("\n");
        assertEquals("# module: db-config", lines[0]);
        assertEquals("DB_HOST=10.0.2.9", lines[1]);
        assertEquals("DB_PORT=5432", lines[2]);
        assertEquals("# module: common-notify", lines[3]);
        assertEquals("NOTIFY_URL=https://im.internal/hook", lines[4]);
    }

    @Test
    @DisplayName("§4.2 模块顺序按传入顺序（请求参数顺序）")
    void preservesModuleOrder() {
        String out = codec.render(List.of(
                new DotenvCodec.ModuleEntries("z-mod", List.of(
                        new DotenvCodec.Entry("Z", "1", false))),
                new DotenvCodec.ModuleEntries("a-mod", List.of(
                        new DotenvCodec.Entry("A", "2", false)))
        ), true, crypto);
        assertTrue(out.indexOf("# module: z-mod") < out.indexOf("# module: a-mod"));
    }

    @Test
    @DisplayName("§4.2 同名 key 后出现的模块覆盖先出现的（last-wins 由顺序保证）")
    void lastWinsByOrder() {
        String out = codec.render(List.of(
                new DotenvCodec.ModuleEntries("first", List.of(
                        new DotenvCodec.Entry("K", "v1", false))),
                new DotenvCodec.ModuleEntries("second", List.of(
                        new DotenvCodec.Entry("K", "v2", false)))
        ), true, crypto);
        // 两行都输出，但第二个在文件后面，dotenv 语义即后者生效
        assertTrue(out.indexOf("K=v1") < out.indexOf("K=v2"));
    }

    @Test
    @DisplayName("§6.4 valuesAlreadyResolved=false 时加密值输出 __MASKED__")
    void masksEncryptedWhenNotResolved() {
        String out = codec.render(List.of(
                new DotenvCodec.ModuleEntries("m", List.of(
                        new DotenvCodec.Entry("PWD", "cipher", true)))
        ), false, crypto);
        assertTrue(out.contains("PWD=__MASKED__"), out);
    }

    // ---------------- 解析（§10.5 粘贴导入） ----------------

    @Test
    @DisplayName("§5.5 解析 dotenv：忽略注释与空行")
    void parseSkipsCommentsAndBlankLines() {
        List<DotenvCodec.Parsed> parsed = codec.parse(
                "# a comment\n\nA=1\n  \nB=2\n");
        assertEquals(2, parsed.size());
        assertEquals("A", parsed.get(0).key());
        assertEquals("1", parsed.get(0).value());
    }

    @Test
    @DisplayName("§5.5 解析带引号的值并反转义")
    void parseUnquotesValues() {
        List<DotenvCodec.Parsed> parsed = codec.parse("P='it'\\''s here'\nQ=\"quoted\"\n");
        assertEquals("it's here", parsed.get(0).value());
        assertEquals("quoted", parsed.get(1).value());
    }

    @Test
    @DisplayName("§10.5 类型推断：PASSWORD|SECRET|TOKEN|KEY 结尾默认加密")
    void infersEncryptedBySuffix() {
        assertTrue(codec.inferEncrypted("DB_PASSWORD"));
        assertTrue(codec.inferEncrypted("API_SECRET"));
        assertTrue(codec.inferEncrypted("GIT_TOKEN"));
        assertTrue(codec.inferEncrypted("SIGN_KEY"));
        assertFalse(codec.inferEncrypted("DB_HOST"));
        assertFalse(codec.inferEncrypted("KEYCLOAK_URL"), "仅后缀匹配");
        assertFalse(codec.inferEncrypted("PASSWORD_HINT"), "仅后缀匹配");
    }

    @Test
    @DisplayName("§5.5 解析时按 key 推断 valueType")
    void parseSetsEncryptedFlag() {
        List<DotenvCodec.Parsed> parsed = codec.parse("DB_HOST=h\nDB_PASSWORD=p\n");
        assertFalse(parsed.get(0).encrypted());
        assertTrue(parsed.get(1).encrypted());
    }

    @Test
    @DisplayName("§5.5 值中含 = 时只按第一个 = 分割")
    void parseSplitsOnFirstEqualsOnly() {
        List<DotenvCodec.Parsed> parsed = codec.parse("URL=https://x/?a=1&b=2\n");
        assertEquals("URL", parsed.get(0).key());
        assertEquals("https://x/?a=1&b=2", parsed.get(0).value());
    }

    @Test
    @DisplayName("§5.5 空文本返回空列表")
    void parseEmptyReturnsEmpty() {
        assertTrue(codec.parse("").isEmpty());
        assertTrue(codec.parse(null).isEmpty());
    }

    @Test
    @DisplayName("§5.5 往返：render 后 parse 得到相同键值")
    void roundTrip() {
        String out = codec.render(List.of(
                new DotenvCodec.ModuleEntries("m", List.of(
                        new DotenvCodec.Entry("PLAIN_KEY", "simple", false),
                        new DotenvCodec.Entry("PWD", "p@ss word", false)))
        ), true, crypto);
        List<DotenvCodec.Parsed> parsed = codec.parse(out);
        assertEquals(2, parsed.size());
        assertEquals("simple", parsed.get(0).value());
        assertEquals("p@ss word", parsed.get(1).value());
    }
}
