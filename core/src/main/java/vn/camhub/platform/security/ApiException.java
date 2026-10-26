package vn.camhub.platform.security;

import java.util.Map;

/**
 * Lỗi nghiệp vụ trả về trình duyệt dạng RFC 7807. {@code code} là mã ổn định cho giao diện dịch sang
 * ngôn ngữ người dùng; {@code message} là mô tả tiếng Việt mặc định.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient Map<String, Object> details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "NOT_FOUND", "Không tìm thấy " + what);
    }

    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "Không có quyền thực hiện thao tác này");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }
}
