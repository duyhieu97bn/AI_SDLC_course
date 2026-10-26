package vn.camhub.device.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Bộ kết nối địa điểm (US-06, ADR-10): agent tại địa điểm mở đường hầm WSS 443 về Tunnel Server. Vòng đời
 * PENDING_ACTIVATION → ONLINE ⇄ OFFLINE; REVOKED khi thu hồi. Mã kích hoạt dùng 1 lần, hết hạn sau 24 giờ.
 */
public record Connector(UUID id, UUID siteId, String name, Status status, Instant codeExpiresAt, Integer socksPort,
        String hostname, String version, Instant lastSeenAt, boolean tunnelUp, Double rxKbps, Double txKbps,
        Instant createdAt, Instant activatedAt) {

    public static final Duration CODE_TTL = Duration.ofHours(24);
    /** Không nhận heartbeat quá thời gian này thì coi là mất liên lạc (heartbeat mỗi 30 giây). */
    public static final Duration OFFLINE_AFTER = Duration.ofSeconds(90);
    public static final int SOCKS_PORT_MIN = 21000;
    public static final int SOCKS_PORT_MAX = 21999;
    public static final int FORWARD_PORT_MIN = 22000;
    public static final int FORWARD_PORT_MAX = 23999;

    public enum Status { PENDING_ACTIVATION, ONLINE, OFFLINE, REVOKED }

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Bỏ ký tự dễ nhầm (0/O, 1/I/L) vì mã được gõ tay hoặc đọc qua điện thoại. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** Mã dạng XXXX-XXXX. */
    public static String newActivationCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Băm mã kích hoạt sau khi chuẩn hóa (bỏ gạch, chữ hoa) để người dùng gõ thế nào cũng khớp. */
    public static String hashCode(String code) {
        return sha256(code == null ? "" : code.replace("-", "").replace(" ", "").toUpperCase());
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean codeExpired(Instant now) {
        return codeExpiresAt == null || !now.isBefore(codeExpiresAt);
    }

    public boolean activated() {
        return status == Status.ONLINE || status == Status.OFFLINE;
    }
}
