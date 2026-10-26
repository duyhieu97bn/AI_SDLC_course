package vn.camhub.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * API stateless: access token JWT ở header Authorization; refresh token ở cookie HttpOnly chỉ gửi tới
 * /api/v1/auth. Kiểm tra quyền theo phạm vi qua {@code hasPermission(...)} (PermissionEvaluator của module access).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {

    static final List<String> PUBLIC = List.of(
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/2fa/**",
            "/api/v1/poc/**", "/internal/**",
            // Liên kết tải clip đã ký 5 phút (US-20), không mang access token
            "/api/v1/exports/*/file",
            // API của Site Connector: xác thực bằng mã kích hoạt / Basic connectorId:secret (US-06)
            "/api/v1/connector-agent/**", "/actuator/health/**", "/actuator/prometheus", "/error",
            // Bắt tay WebSocket không mang được header Authorization; xác thực ở frame STOMP CONNECT
            "/ws", "/ws/**");

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtService jwt, ObjectProvider<SessionValidator> sessions,
            ObjectMapper json) throws Exception {
        AuthenticationEntryPoint unauthorized = (req, res, ex) -> writeProblem(res, json, 401, "UNAUTHENTICATED",
                "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");
        http
                .csrf(c -> c.disable()) // không dùng cookie cho xác thực API; cookie refresh có SameSite=Strict
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(PUBLIC.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(jwt.decoder()).jwtAuthenticationConverter(converter(sessions)))
                        .authenticationEntryPoint(unauthorized))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((req, res, ex) -> writeProblem(res, json, 403, "FORBIDDEN",
                                "Không có quyền thực hiện thao tác này")));
        return http.build();
    }

    private static Converter<Jwt, AbstractAuthenticationToken> converter(ObjectProvider<SessionValidator> sessions) {
        return token -> {
            CurrentUser user = JwtService.toUser(token);
            SessionValidator validator = sessions.getIfAvailable();
            if (validator != null && !validator.isActive(user)) {
                throw new BadCredentialsException("Phiên đã bị thu hồi");
            }
            return new UserAuthentication(user, token);
        };
    }

    @Bean
    static DefaultMethodSecurityExpressionHandler methodSecurityExpressionHandler(
            ObjectProvider<PermissionEvaluator> evaluator) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        evaluator.ifAvailable(handler::setPermissionEvaluator);
        return handler;
    }

    static void writeProblem(HttpServletResponse res, ObjectMapper json, int status, String code, String detail)
            throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        json.writeValue(res.getOutputStream(), Map.of("status", status, "code", code, "detail", detail));
    }

    /** Authentication mang {@link CurrentUser} làm principal. */
    static final class UserAuthentication extends AbstractAuthenticationToken {
        private final CurrentUser user;
        private final transient Jwt token;

        UserAuthentication(CurrentUser user, Jwt token) {
            super(AuthorityUtils.NO_AUTHORITIES);
            this.user = user;
            this.token = token;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return token;
        }

        @Override
        public Object getPrincipal() {
            return user;
        }
    }
}
