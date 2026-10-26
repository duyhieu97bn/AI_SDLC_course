package vn.camhub.platform.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/** Cấp access token JWT HS256, hết hạn sau 15 phút (SADD 3.7.1). */
@Service
public class JwtService {

    @ConfigurationProperties("camhub.security.jwt")
    public record JwtProperties(String secret, Duration accessTokenTtl) {
        public JwtProperties {
            if (accessTokenTtl == null) {
                accessTokenTtl = Duration.ofMinutes(15);
            }
        }
    }

    static final String ISSUER = "camhub";
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(JwtProperties props, Clock clock) {
        if (props.secret() == null || props.secret().isBlank()) {
            throw new IllegalStateException("Chưa cấu hình camhub.security.jwt.secret");
        }
        byte[] raw = Base64.getDecoder().decode(props.secret().trim());
        if (raw.length < 32) {
            throw new IllegalStateException("camhub.security.jwt.secret phải dài tối thiểu 32 byte");
        }
        SecretKey key = new SecretKeySpec(raw, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.ttl = props.accessTokenTtl();
        this.clock = clock;
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }

    public IssuedToken issue(CurrentUser user) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.userId().toString())
                .issuedAt(now)
                .expiresAt(exp)
                .claim("org", user.organizationId().toString())
                .claim("sid", user.sessionId().toString())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, exp);
    }

    JwtDecoder decoder() {
        return decoder;
    }

    /** Giải mã và kiểm tra chữ ký, hạn dùng của access token (dùng cho WebSocket CONNECT). */
    public CurrentUser parse(String token) {
        try {
            return toUser(decoder.decode(token));
        } catch (org.springframework.security.oauth2.jwt.JwtException | IllegalArgumentException e) {
            throw new org.springframework.security.authentication.BadCredentialsException("Access token không hợp lệ");
        }
    }

    static CurrentUser toUser(org.springframework.security.oauth2.jwt.Jwt jwt) {
        return new CurrentUser(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("org")),
                UUID.fromString(jwt.getClaimAsString("sid")));
    }
}
