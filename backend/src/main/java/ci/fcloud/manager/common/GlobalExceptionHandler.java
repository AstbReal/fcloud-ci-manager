package ci.fcloud.manager.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常 → ApiResponse。任何变量值都不得出现在 message 中。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(200)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("参数校验失败");
        return ResponseEntity.status(200).body(ApiResponse.error(40000, msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegal(IllegalArgumentException e) {
        return ResponseEntity.status(200).body(ApiResponse.error(40000, e.getMessage()));
    }

    /**
     * 上传超过容器级上限（默认 2GB，见 server.servlet.multipart）时给出业务提示而非 500。
     * 单文件 50MB 的限制在 FileService 里单独校验，走的是普通 BizException。
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(200)
                .body(ApiResponse.error(40000, "上传内容超过服务端上限（默认 2GB）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        // 不把内部异常信息透传给前端（可能夹带敏感值），但记完整堆栈便于排障
        log.error("unhandled exception: {}", e.getMessage(), e);
        return ResponseEntity.status(200).body(ApiResponse.error(50000, "服务内部错误"));
    }
}
