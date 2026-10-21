package ci.fcloud.hub.common;

import ci.fcloud.hub.config.FcloudProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §6.5 加密服务单测：AES-256-GCM 往返、密文格式、主密钥来源优先级。
 */
class CryptoServiceTest {

    private FcloudProperties propsWithKey(String base64Key) {
        FcloudProperties p = new FcloudProperties();
        p.setMasterKey(base64Key);
        return p;
    }

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptDecryptRoundTrip() {
        CryptoService svc = new CryptoService(propsWithKey(KEY));
        String plain = "p@ss word #$'\"\n换行";
        String enc = svc.encrypt(plain);
        assertNotNull(enc);
        assertNotEquals(plain, enc);
        assertEquals(plain, svc.decrypt(enc));
    }

    @Test
    void cipherTextIsBase64OfIvPlusCipherPlusTag() {
        CryptoService svc = new CryptoService(propsWithKey(KEY));
        byte[] raw = Base64.getDecoder().decode(svc.encrypt("hello"));
        // IV(12) + ciphertext(>=1) + tag(16)
        assertTrue(raw.length > 12 + 16, "packed length must exceed IV+tag");
    }

    @Test
    void samePlainTextYieldsDifferentCipherTexts() {
        CryptoService svc = new CryptoService(propsWithKey(KEY));
        String a = svc.encrypt("same");
        String b = svc.encrypt("same");
        assertNotEquals(a, b, "random IV must make ciphertexts differ");
    }

    @Test
    void wrongKeyCannotDecrypt() {
        CryptoService a = new CryptoService(propsWithKey(KEY));
        String enc = a.encrypt("secret");
        byte[] other = new byte[32];
        other[0] = 1;
        CryptoService b = new CryptoService(propsWithKey(
                Base64.getEncoder().encodeToString(other)));
        assertThrows(IllegalStateException.class, () -> b.decrypt(enc));
    }

    @Test
    void masterKeyFromFileIsUsedAndPersisted() throws Exception {
        Path tmp = Files.createTempFile("fcloud-master", ".key");
        Files.deleteIfExists(tmp);
        FcloudProperties p = new FcloudProperties();
        p.setMasterKeyFile(tmp.toString());

        CryptoService first = new CryptoService(p);
        String enc = first.encrypt("from-file");
        assertTrue(Files.exists(tmp), "master key must be persisted");

        // 第二次构造（无环境变量）应复用同一文件密钥
        CryptoService second = new CryptoService(p);
        assertEquals("from-file", second.decrypt(enc));
    }

    @Test
    void envMasterKeyTakesPrecedenceOverFile() throws Exception {
        Path tmp = Files.createTempFile("fcloud-master2", ".key");
        Files.deleteIfExists(tmp);
        FcloudProperties p = new FcloudProperties();
        p.setMasterKeyFile(tmp.toString());
        p.setMasterKey(KEY);

        CryptoService svc = new CryptoService(p);
        // 指定环境变量时不应生成文件
        assertFalse(Files.exists(tmp), "explicit master key must not write file");
        assertEquals("x", svc.decrypt(svc.encrypt("x")));
    }

    @Test
    void nullValuesPassThrough() {
        CryptoService svc = new CryptoService(propsWithKey(KEY));
        assertNull(svc.encrypt(null));
        assertNull(svc.decrypt(null));
    }
}
