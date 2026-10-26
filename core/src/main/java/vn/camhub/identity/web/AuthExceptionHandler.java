package vn.camhub.identity.web;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.camhub.platform.security.ApiException;

@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler {

    @ExceptionHandler(AuthController.ClearCookieException.class)
    ResponseEntity<ProblemDetail> refreshFailed(AuthController.ClearCookieException e) {
        int status = 401;
        String code = "SESSION_EXPIRED";
        String detail = "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại";
        if (e.getCause() instanceof ApiException api) {
            status = api.status();
            code = api.code();
            detail = api.getMessage();
        }
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        pd.setProperty("code", code);
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, AuthController.cookie("", Duration.ZERO).toString())
                .body(pd);
    }
}
