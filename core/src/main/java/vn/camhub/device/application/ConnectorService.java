package vn.camhub.device.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.api.DeviceControlPort.DiscoveredDevice;
import vn.camhub.device.domain.Connector;
import vn.camhub.device.domain.Connector.Status;
import vn.camhub.device.infrastructure.ConnectorRepository;
import vn.camhub.device.infrastructure.ConnectorRepository.Forward;
import vn.camhub.organization.api.OrganizationApi;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.realtime.RealtimePublisher;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Bộ kết nối địa điểm (US-06, TS-06): Admin tạo bộ kết nối và mã kích hoạt; Connector kích hoạt bằng mã (1 lần,
 * 24 giờ), gửi heartbeat 30 giây/lần để nhận cấu hình đường hầm (cổng SOCKS, cổng chuyển tiếp RTSP) và việc cần làm
 * (dò WS-Discovery tại địa điểm). Không có heartbeat 90 giây → OFFLINE.
 */
@Service
public class ConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);
    static final int HEARTBEAT_SECONDS = 30;

    public record ConnectorView(UUID id, UUID siteId, String siteName, String name, Status status, Instant lastSeenAt,
            boolean tunnelUp, String version, String hostname, Double rxKbps, Double txKbps, Instant codeExpiresAt,
            Instant createdAt) {
    }

    public record Created(ConnectorView connector, String activationCode, Instant expiresAt, String installCommand) {
    }

    public record Activation(UUID connectorId, String secret, Tunnel tunnel) {
    }

    public record Tunnel(String url, String user, String password) {
    }

    public record Heartbeat(String version, Long uptimeSeconds, Boolean tunnelUp, Long rxBytes, Long txBytes,
            Double rxKbps, Double txKbps) {
    }

    public record AgentConfig(String configVersion, int socksPort, List<Forward> forwards) {
    }

    public record AgentTask(UUID id, String type, int timeoutSeconds) {
    }

    public record HeartbeatReply(AgentConfig config, List<AgentTask> tasks) {
    }

    private final ConnectorRepository repo;
    private final OrganizationApi organizations;
    private final AccessApi access;
    private final CryptoService crypto;
    private final TransactionTemplate tx;
    private final RealtimePublisher realtime;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;
    private final String publicUrl;

    public ConnectorService(ConnectorRepository repo, OrganizationApi organizations, AccessApi access,
            CryptoService crypto, TransactionTemplate tx, RealtimePublisher realtime, ApplicationEventPublisher events,
            ObjectMapper json, Clock clock, @Value("${camhub.notification.base-url:https://localhost}") String publicUrl) {
        this.repo = repo;
        this.organizations = organizations;
        this.access = access;
        this.crypto = crypto;
        this.tx = tx;
        this.realtime = realtime;
        this.events = events;
        this.json = json;
        this.clock = clock;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    // ---------------------------------------------------------------- Admin (US-06)

    public List<ConnectorView> list(UUID actor) {
        requireAdmin(actor);
        return repo.all().stream().map(this::view).toList();
    }

    public Created create(UUID actor, UUID siteId, String name) {
        requireAdmin(actor);
        String site = organizations.siteName(siteId).orElseThrow(() -> ApiException.notFound("địa điểm"));
        String code = Connector.newActivationCode();
        Instant expires = clock.instant().plus(Connector.CODE_TTL);
        UUID id = UUID.randomUUID();
        try {
            tx.executeWithoutResult(s -> {
                repo.insert(id, siteId, name == null || name.isBlank() ? "Bộ kết nối " + site : name.trim(),
                        Connector.hashCode(code), expires, actor);
                events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CONNECTOR", id.toString(),
                        Map.of("operation", "CREATE_CONNECTOR", "siteName", site)));
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(409, "CONNECTOR_EXISTS", "Địa điểm đã có bộ kết nối");
        }
        return new Created(view(repo.find(id).orElseThrow()), code, expires, installCommand(code));
    }

    /** Tạo mã mới (mã cũ hết hạn hoặc bị lộ); chỉ khi chưa kích hoạt. */
    public Created newCode(UUID actor, UUID id) {
        requireAdmin(actor);
        Connector c = repo.find(id).orElseThrow(() -> ApiException.notFound("bộ kết nối"));
        if (c.status() != Status.PENDING_ACTIVATION) {
            throw new ApiException(409, "ALREADY_ACTIVATED", "Bộ kết nối đã kích hoạt");
        }
        String code = Connector.newActivationCode();
        Instant expires = clock.instant().plus(Connector.CODE_TTL);
        tx.executeWithoutResult(s -> repo.newCode(id, Connector.hashCode(code), expires));
        return new Created(view(repo.find(id).orElseThrow()), code, expires, installCommand(code));
    }

    public void revoke(UUID actor, UUID id) {
        requireAdmin(actor);
        Connector c = repo.find(id).orElseThrow(() -> ApiException.notFound("bộ kết nối"));
        tx.executeWithoutResult(s -> {
            repo.revoke(id);
            events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CONNECTOR", id.toString(),
                    Map.of("operation", "REVOKE_CONNECTOR", "targetName", c.name())));
            events.publishEvent(new TunnelAuthWriter.TunnelAuthChanged());
        });
    }

    // ---------------------------------------------------------------- Connector (API agent)

    /**
     * Kích hoạt bằng mã (US-06 AC1); mã quá 24 giờ bị từ chối (AC2). {@code origin}: địa chỉ HTTPS Connector đã dùng
     * để gọi Core; đường hầm đi qua cùng địa chỉ (Nginx /tunnel/).
     */
    public Activation activate(String code, String hostname, String version, String origin) {
        String base = origin == null || origin.isBlank() ? publicUrl
                : origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        ConnectorRepository.Ref ref = repo.byCode(Connector.hashCode(code))
                .orElseThrow(() -> new ApiException(404, "INVALID_CODE", "Mã kích hoạt không đúng"));
        return OrganizationContext.callAs(ref.organizationId(), () -> {
            Connector c = repo.find(ref.id()).orElseThrow();
            if (c.status() != Status.PENDING_ACTIVATION) {
                throw new ApiException(409, "ALREADY_ACTIVATED", "Mã kích hoạt đã được dùng");
            }
            if (c.codeExpired(clock.instant())) {
                throw new ApiException(410, "ACTIVATION_EXPIRED", "Mã kích hoạt đã hết hạn, Admin cần tạo mã mới");
            }
            String secret = Connector.newSecret();
            String tunnelPassword = Connector.newSecret();
            for (int attempt = 0; attempt < 50; attempt++) {
                int socks = Connector.SOCKS_PORT_MIN + ThreadLocalRandom.current()
                        .nextInt(Connector.SOCKS_PORT_MAX - Connector.SOCKS_PORT_MIN + 1);
                if (repo.socksPortTaken(socks)) {
                    continue;
                }
                try {
                    Boolean ok = tx.execute(s -> {
                        boolean done = repo.activate(c.id(), Connector.sha256(secret), crypto.encrypt(tunnelPassword),
                                socks, hostname, version);
                        if (done) {
                            events.publishEvent(AuditableEvent.of(null, "CHANGE_CONFIG", "CONNECTOR",
                                    c.id().toString(), Map.of("operation", "ACTIVATE_CONNECTOR",
                                            "hostname", hostname == null ? "" : hostname)));
                            events.publishEvent(new TunnelAuthWriter.TunnelAuthChanged());
                        }
                        return done;
                    });
                    if (!Boolean.TRUE.equals(ok)) {
                        throw new ApiException(409, "ALREADY_ACTIVATED", "Mã kích hoạt đã được dùng");
                    }
                    log.info("Bộ kết nối {} đã kích hoạt từ {}", c.id(), hostname);
                    push(c.id());
                    return new Activation(c.id(), secret, new Tunnel(base + "/tunnel/", c.id().toString(),
                            tunnelPassword));
                } catch (DuplicateKeyException e) {
                    // cổng SOCKS vừa bị bộ kết nối khác lấy: thử cổng khác
                }
            }
            throw new IllegalStateException("Hết cổng SOCKS cho đường hầm");
        });
    }

    /** Xác thực Basic connectorId:secret; trả về Tổ chức của bộ kết nối. */
    public UUID authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            throw unauthorized();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unauthorized();
        }
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            throw unauthorized();
        }
        UUID id;
        try {
            id = UUID.fromString(decoded.substring(0, colon));
        } catch (IllegalArgumentException e) {
            throw unauthorized();
        }
        String secret = decoded.substring(colon + 1);
        UUID org = repo.organizationOf(id).orElseThrow(ConnectorService::unauthorized);
        String expected = OrganizationContext.callAs(org, () -> repo.secretHash(id).orElse(null));
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                Connector.sha256(secret).getBytes(StandardCharsets.US_ASCII))) {
            throw unauthorized();
        }
        return org;
    }

    /** Gọi trong ngữ cảnh Tổ chức của bộ kết nối. */
    public HeartbeatReply heartbeat(UUID id, Heartbeat hb) {
        Status before = tx.execute(s -> repo.heartbeat(id, hb.version(), Boolean.TRUE.equals(hb.tunnelUp()),
                hb.rxKbps(), hb.txKbps()));
        if (before != Status.ONLINE) {
            log.info("Bộ kết nối {} trực tuyến", id);
            push(id);
        }
        Connector c = repo.find(id).orElseThrow();
        List<Forward> forwards = repo.forwards(id);
        List<AgentTask> tasks = tx.execute(s -> repo.takePending(id)).stream()
                .map(t -> new AgentTask(t.id(), t.type(), t.timeoutSeconds())).toList();
        return new HeartbeatReply(new AgentConfig(configVersion(c.socksPort(), forwards), c.socksPort(), forwards),
                tasks);
    }

    public void taskResult(UUID connectorId, UUID taskId, List<DiscoveredDevice> devices, String error) {
        String result;
        try {
            result = json.writeValueAsString(devices == null ? List.of() : devices);
        } catch (Exception e) {
            result = "[]";
        }
        String r = result;
        if (!Boolean.TRUE.equals(tx.execute(s -> repo.completeTask(connectorId, taskId, r, error)))) {
            throw ApiException.notFound("task");
        }
    }

    // ---------------------------------------------------------------- dò tìm qua Connector (UC-10)

    /** Giao việc dò WS-Discovery cho Connector và đợi kết quả (≤ thời gian dò + một chu kỳ heartbeat). */
    public List<DiscoveredDevice> discover(UUID connectorId, int timeoutSeconds) {
        Connector c = repo.find(connectorId).orElseThrow();
        if (c.status() != Status.ONLINE) {
            throw new ApiException(503, "CONNECTOR_OFFLINE", "Bộ kết nối địa điểm đang mất liên lạc");
        }
        UUID task = tx.execute(s -> repo.insertTask(connectorId, "DISCOVERY", timeoutSeconds));
        Instant deadline = clock.instant().plusSeconds(timeoutSeconds + HEARTBEAT_SECONDS + 10L);
        while (clock.instant().isBefore(deadline)) {
            Optional<ConnectorRepository.Task> t = repo.task(task);
            if (t.isPresent() && "DONE".equals(t.get().status())) {
                try {
                    return json.readValue(t.get().result(), new TypeReference<List<DiscoveredDevice>>() {
                    });
                } catch (Exception e) {
                    return List.of();
                }
            }
            if (t.isPresent() && "FAILED".equals(t.get().status())) {
                throw new ApiException(502, "DISCOVERY_FAILED", "Bộ kết nối không dò được: " + t.get().error());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ApiException(504, "CONNECTOR_TIMEOUT", "Bộ kết nối không trả kết quả dò tìm");
    }

    // ---------------------------------------------------------------- mất liên lạc

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void markStale() {
        for (ConnectorRepository.Ref ref : repo.activeAcrossOrganizations()) {
            OrganizationContext.runAs(ref.organizationId(), this::markStaleOrganization);
        }
    }

    public void markStaleOrganization() {
        for (UUID id : repo.staleOnline(clock.instant().minus(Connector.OFFLINE_AFTER))) {
            tx.executeWithoutResult(s -> repo.setStatus(id, Status.OFFLINE));
            log.warn("Bộ kết nối {} mất liên lạc", id);
            push(id);
        }
    }

    // ---------------------------------------------------------------- chung

    static String configVersion(Integer socksPort, List<Forward> forwards) {
        StringBuilder sb = new StringBuilder(String.valueOf(socksPort));
        forwards.forEach(f -> sb.append('|').append(f.port()).append('>').append(f.host()).append(':')
                .append(f.targetPort()));
        return Connector.sha256(sb.toString()).substring(0, 16);
    }

    private String installCommand(String code) {
        return "docker run -d --name camhub-connector --restart unless-stopped -e CAMHUB_SERVER=" + publicUrl
                + " -e CAMHUB_ACTIVATION_CODE=" + code
                + " -v camhub-connector:/var/lib/camhub-connector --network host camhub/site-connector:latest";
    }

    private void push(UUID id) {
        repo.find(id).ifPresent(c -> realtime.toOrganization(OrganizationContext.require(), "connector-status",
                view(c)));
    }

    private ConnectorView view(Connector c) {
        return new ConnectorView(c.id(), c.siteId(), organizations.siteName(c.siteId()).orElse(""), c.name(),
                c.status(), c.lastSeenAt(), c.tunnelUp(), c.version(), c.hostname(), c.rxKbps(), c.txKbps(),
                c.codeExpiresAt(), c.createdAt());
    }

    private void requireAdmin(UUID actor) {
        if (!access.hasOrganizationWide(actor, Permission.MANAGE_SYSTEM)) {
            throw ApiException.forbidden();
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(401, "CONNECTOR_UNAUTHORIZED", "Bộ kết nối chưa kích hoạt hoặc đã bị thu hồi");
    }
}
