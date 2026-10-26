package vn.camhub.platform.security;

import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Mọi lỗi API theo RFC 7807 (SADD 3.7.1), luôn có trường {@code code} để giao diện dịch. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
        pd.setProperty("code", e.code());
        e.details().forEach(pd::setProperty);
        return pd;
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ProblemDetail denied(RuntimeException e) {
        return api(ApiException.forbidden());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(), f -> String.valueOf(f.getDefaultMessage()), (a, b) -> a));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ");
        pd.setProperty("code", "VALIDATION");
        pd.setProperty("fields", fields);
        return pd;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        log.info("Vi phạm ràng buộc dữ liệu: {}", e.getMostSpecificCause().getMessage());
        String msg = e.getMostSpecificCause().getMessage();
        if (msg != null && msg.contains("duplicate key")) {
            return api(ApiException.conflict("DUPLICATE", "Dữ liệu đã tồn tại"));
        }
        if (msg != null && msg.contains("foreign key")) {
            return api(ApiException.conflict("IN_USE", "Dữ liệu đang được sử dụng hoặc tham chiếu không tồn tại"));
        }
        return api(ApiException.badRequest("CONSTRAINT", "Dữ liệu vi phạm ràng buộc"));
    }
}
