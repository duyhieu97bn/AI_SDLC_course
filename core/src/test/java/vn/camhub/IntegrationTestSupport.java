package vn.camhub;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.camhub.device.api.DeviceControlPort;
import vn.camhub.device.infrastructure.GatewayMonitorClient;
import vn.camhub.device.infrastructure.MediaMtxClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import com.redis.testcontainers.RedisContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Nền chung cho test tích hợp: PostgreSQL 16 và RabbitMQ 3.13 thật (Testcontainers), dùng chung cho mọi lớp IT.
 *
 * <p>Ứng dụng kết nối bằng {@code camhub_app} (không phải owner, không BYPASSRLS) giống production,
 * còn Flyway chạy bằng owner. Nếu dùng tài khoản superuser mặc định thì RLS bị bỏ qua và test vô nghĩa.
 */
@Tag("integration")
@SpringBootTest
public abstract class IntegrationTestSupport {

    // MediaMTX và Device Gateway không chạy trong test tích hợp: thay bằng mock, mọi lớp IT dùng chung một context
    @MockitoBean
    protected MediaMtxClient mediamtx;
    @MockitoBean
    protected DeviceControlPort devices;
    @MockitoBean
    protected GatewayMonitorClient monitor;
    // MinIO (clip đã xuất) cũng thay bằng mock
    @MockitoBean
    protected vn.camhub.platform.storage.ObjectStorage objectStorage;
    // Telegram/Zalo (S7)
    @MockitoBean
    protected vn.camhub.notification.infrastructure.ChatSenders chat;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("camhub")
            .withUsername("camhub")
            .withPassword("camhub")
            .withInitScript("db/test-init-roles.sql");

    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7.4-alpine"));

    public static final String ADMIN_EMAIL = "admin@camhub.test";
    public static final String ADMIN_PASSWORD = "Admin-Pass-2026";

    public static final java.nio.file.Path TUNNEL_AUTH_DIR;

    static {
        try {
            TUNNEL_AUTH_DIR = java.nio.file.Files.createTempDirectory("tunnel-auth");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        POSTGRES.start();
        RABBIT.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "camhub_app");
        r.add("spring.datasource.password", () -> "camhub_app");
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/testmigration");
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("spring.rabbitmq.virtual-host", () -> "/");
        r.add("camhub.crypto.master-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("camhub.security.jwt.secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        r.add("camhub.bootstrap.admin-email", () -> ADMIN_EMAIL);
        r.add("camhub.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        r.add("camhub.media.token.secret", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        // Tắt lịch tự chạy của outbox, test gọi publishPending() trực tiếp
        r.add("camhub.outbox.initial-delay", () -> "1h");
        r.add("camhub.outbox.poll-interval", () -> "1h");
        // Authfile của Tunnel Server ghi vào thư mục tạm (S6)
        r.add("camhub.tunnel.auth-file", () -> TUNNEL_AUTH_DIR.resolve("users.json").toString());
    }
}
