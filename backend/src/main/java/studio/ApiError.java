package studio;

import org.springframework.http.HttpStatus;

public class ApiError extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public ApiError(HttpStatus status, String code, String message) { super(message); this.status = status; this.code = code; }
    public static ApiError bad(String message) { return new ApiError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    public static ApiError missing() { return new ApiError(HttpStatus.NOT_FOUND, "NOT_FOUND", "资源不存在或不属于当前用户"); }
}
