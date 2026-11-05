package ci.fcloud.manager.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import ci.fcloud.manager.config.FcloudProperties;
import org.springframework.stereotype.Service;

/**
 * §6.5 AES-256-GCM。存储格式：Base64(IV(12B) + ciphertext + tag)。
 * 主密钥绝不进入日志与备份包。
 */
@Service
public class CryptoService {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(FcloudProperties props) {
        byte[] raw = resolveMasterKey(props);
        if (raw.length != 32) {
            throw new IllegalStateException("master key must be 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] out = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[IV_LEN + out.length];
            System.arraycopy(iv, 0, packed, 0, IV_LEN);
            System.arraycopy(out, 0, packed, IV_LEN, out.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed");
        }
    }

    public String decrypt(String packed) {
        if (packed == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(packed);
            if (all.length <= IV_LEN) {
                throw new IllegalArgumentException("invalid ciphertext");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed");
        }
    }

    private static byte[] resolveMasterKey(FcloudProperties props) {
        // 1) 环境变量 FCLOUD_MASTER_KEY（Base64 32 字节）
        if (props.getMasterKey() != null && !props.getMasterKey().isBlank()) {
            return Base64.getDecoder().decode(props.getMasterKey().trim());
        }
        // 2) master-key 文件
        Path file = Paths.get(props.getMasterKeyFile());
        if (Files.exists(file)) {
            try {
                byte[] existing = Base64.getDecoder().decode(Files.readString(file).trim());
                if (existing.length == 32) {
                    return existing;
                }
            } catch (Exception ignored) {
                // fallthrough to generate
            }
        }
        // 3) 自动生成并落盘（权限 600）
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, Base64.getEncoder().encodeToString(generated));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows 本地开发无 POSIX 权限，忽略
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot persist master key: " + e.getMessage());
        }
        return generated;
    }
}
