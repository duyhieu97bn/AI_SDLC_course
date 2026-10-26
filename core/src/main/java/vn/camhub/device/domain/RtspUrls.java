package vn.camhub.device.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Tách/ghép tài khoản trong URL RTSP: DB chỉ lưu URL không tài khoản, mật khẩu mã hóa riêng (INV-17). */
public final class RtspUrls {

    public record Parsed(String urlWithoutCredentials, String username, String password, String host, int port) {
    }

    private RtspUrls() {
    }

    public static Parsed parse(String url) {
        try {
            URI u = new URI(url.trim());
            if (!"rtsp".equalsIgnoreCase(u.getScheme()) && !"rtsps".equalsIgnoreCase(u.getScheme())) {
                throw new IllegalArgumentException("URL phải bắt đầu bằng rtsp://");
            }
            if (u.getHost() == null) {
                throw new IllegalArgumentException("URL RTSP thiếu địa chỉ máy");
            }
            String user = null;
            String pass = null;
            if (u.getRawUserInfo() != null) {
                String[] parts = u.getRawUserInfo().split(":", 2);
                user = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                pass = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            }
            int port = u.getPort() > 0 ? u.getPort() : 554;
            URI clean = new URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getRawPath() == null ? "" : u.getPath(),
                    null, null);
            String s = clean.toString() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            return new Parsed(s, user, pass, u.getHost(), port);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL RTSP không hợp lệ");
        }
    }

    /** URL đầy đủ để MediaMTX kéo luồng; chỉ dùng trong bộ nhớ khi gửi cấu hình cho MediaMTX. */
    public static String withCredentials(String url, String username, String password) {
        if (username == null || username.isBlank()) {
            return url;
        }
        String userInfo = enc(username) + ":" + enc(password == null ? "" : password);
        int schemeEnd = url.indexOf("://");
        return url.substring(0, schemeEnd + 3) + userInfo + "@" + url.substring(schemeEnd + 3);
    }

    /** Thay máy:cổng của URL (không tài khoản), giữ đường dẫn và query: dùng cho luồng đi qua đường hầm. */
    public static String withHostPort(String url, String host, int port) {
        try {
            URI u = new URI(url.trim());
            URI out = new URI(u.getScheme(), null, host, port, u.getPath(), null, null);
            return out + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL RTSP không hợp lệ");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
