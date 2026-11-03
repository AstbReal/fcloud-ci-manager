package ci.fcloud.manager.common;

import lombok.Getter;

/**
 * 业务异常。code 会原样出现在 ApiResponse.code 中。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException badRequest(String message) {
        return new BizException(40000, message);
    }

    public static BizException notFound(String message) {
        return new BizException(40400, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(40300, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(40100, message);
    }

    public static BizException conflict(String message) {
        return new BizException(40900, message);
    }
}
