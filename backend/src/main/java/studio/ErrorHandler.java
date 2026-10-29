package studio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;

@RestControllerAdvice
public class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    public record ErrorBody(String code, String message) {}
    @ExceptionHandler(ApiError.class)
    ResponseEntity<ErrorBody> api(ApiError e) { return ResponseEntity.status(e.status).body(new ErrorBody(e.code, e.getMessage())); }
    @ExceptionHandler({MaxUploadSizeExceededException.class, MissingServletRequestPartException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    ResponseEntity<ErrorBody> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", "请求格式错误或文件超过 10MB 限制"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("Request failed", e);
        return ResponseEntity.internalServerError().body(new ErrorBody("INTERNAL_ERROR", "服务暂时不可用，请稍后重试"));
    }
}
