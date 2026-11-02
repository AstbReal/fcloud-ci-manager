package ci.fcloud.hub.file;

import ci.fcloud.hub.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * §2.4 fileKey 规则：只允许扁平文件名（模块 × 环境已是唯一维度），
 * 以及写入前的路径越界护栏。
 */
class FileServicePathTest {

    private static final Path DIR = Paths.get("storage", "app", "module", "prod", "v1");

    // ---- fileKey 校验 ----

    @Test
    @DisplayName("普通文件名：通过，并去掉首尾空白")
    void acceptsPlainKey() {
        assertEquals("Dockerfile", FileService.requireFlatFileKey("Dockerfile"));
        assertEquals("nginx.conf", FileService.requireFlatFileKey("  nginx.conf  "));
        assertEquals("start.template", FileService.requireFlatFileKey("start.template"));
    }

    @Test
    @DisplayName("带目录的 key：拒绝")
    void rejectsNestedKey() {
        assertThrows(BizException.class,
                () -> FileService.requireFlatFileKey("deployment/deployment.yaml"));
        assertThrows(BizException.class,
                () -> FileService.requireFlatFileKey("deployment\\deployment.yaml"));
    }

    @Test
    @DisplayName("空 key 或 .. ：拒绝")
    void rejectsEmptyOrDots() {
        assertThrows(BizException.class, () -> FileService.requireFlatFileKey(null));
        assertThrows(BizException.class, () -> FileService.requireFlatFileKey("   "));
        assertThrows(BizException.class, () -> FileService.requireFlatFileKey("."));
        assertThrows(BizException.class, () -> FileService.requireFlatFileKey(".."));
    }

    // ---- 路径护栏（防御性，正常调用不会走到） ----

    @Test
    @DisplayName("扁平 key：落在版本目录下")
    void plainKeyResolves() {
        Path p = FileService.resolveTarget(DIR, "Dockerfile");
        assertEquals(DIR.resolve("Dockerfile").normalize(), p);
    }

    @Test
    @DisplayName("包含 .. 的 key：拒绝")
    void rejectsTraversal() {
        assertThrows(BizException.class,
                () -> FileService.resolveTarget(DIR, "../../etc/passwd"));
    }

    @Test
    @DisplayName("绝对路径：拒绝")
    void rejectsAbsolute() {
        assertThrows(BizException.class,
                () -> FileService.resolveTarget(DIR, "/etc/passwd"));
    }
}
