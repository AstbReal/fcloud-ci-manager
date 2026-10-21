package ci.fcloud.hub.mcp.tool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.web.multipart.MultipartFile;

/**
 * 内存版 MultipartFile。
 *
 * <p>MCP 传的是 base64（JSON 没有二进制类型），但要复用
 * {@code FileService.upload(multipart)} 的落盘/版本/大小校验逻辑，
 * 所以造一个最小的 MultipartFile 适配器，而不是给 FileService 开新入口。</p>
 */
public class InlineMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    public InlineMultipartFile(String name, String originalFilename, String contentType,
                               byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content == null ? new byte[0] : content;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() {
        return content;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(Path dest) throws IOException {
        Files.write(dest, content);
    }

    @Override
    public void transferTo(java.io.File dest) throws IOException {
        Files.write(dest.toPath(), content);
    }
}
