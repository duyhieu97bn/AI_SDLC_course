package camhub.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tài khoản cho kiểm thử tải: {@code loadtest-001@camhub.local} … vai trò Operator, phạm vi toàn Tổ chức. Tạo bằng
 * API quản trị nếu chưa có (email đã tồn tại thì bỏ qua), nên chạy lại nhiều lần được.
 */
final class LoadUsers {

    static final String PASSWORD = "Load-Pass-2026";
    private static final Pattern TOKEN = Pattern.compile("\"accessToken\"\s*:\s*\"([^\"]+)\"");
    private static final Pattern OPERATOR = Pattern.compile("\"id\"\s*:\s*\"([0-9a-f-]{36})\"[^{}]*\"code\"\s*:\s*\"OPERATOR\"");

    private LoadUsers() {
    }

    static String email(int i) {
        return String.format("loadtest-%03d@camhub.local", i);
    }

    static void ensure(String base, String adminEmail, String adminPassword, int count) {
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException("Thiếu CAMHUB_ADMIN_PASSWORD để tạo tài khoản kiểm thử tải");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String token = match(TOKEN, send(http, base, "POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + adminEmail + "\",\"password\":\"" + adminPassword + "\"}").body(), "accessToken");
        String roleId = match(OPERATOR, send(http, base, "GET", "/api/v1/roles", token, null).body(), "vai trò OPERATOR");
        int created = 0;
        for (int i = 1; i <= count; i++) {
            String body = "{\"email\":\"" + email(i) + "\",\"displayName\":\"Kiểm thử tải " + i + "\",\"password\":\""
                    + PASSWORD + "\",\"assignments\":[{\"roleId\":\"" + roleId + "\",\"scopeType\":\"ORGANIZATION\"}]}";
            HttpResponse<String> r = send(http, base, "POST", "/api/v1/users", token, body);
            if (r.statusCode() == 201) {
                created++;
            } else if (r.statusCode() != 409) {
                throw new IllegalStateException("Tạo " + email(i) + " lỗi " + r.statusCode() + ": " + r.body());
            }
        }
        System.out.printf("Tài khoản kiểm thử tải: %d (mới tạo %d)%n", count, created);
    }

    private static HttpResponse<String> send(HttpClient http, String base, String method, String path, String token,
            String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Không gọi được " + base + path + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String match(Pattern p, String body, String what) {
        Matcher m = p.matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("Không đọc được " + what + " từ phản hồi: " + body);
        }
        return m.group(1);
    }
}
