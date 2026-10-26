package vn.camhub.device.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vn.camhub.device.api.CameraChanged;
import vn.camhub.device.domain.Camera;
import vn.camhub.device.domain.RtspUrls;
import vn.camhub.device.infrastructure.CameraRepository;
import vn.camhub.device.infrastructure.GatewayMonitorClient;
import vn.camhub.device.infrastructure.MediaMtxClient;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Giữ MediaMTX và Device Gateway khớp với danh sách camera trong DB:
 * <ul>
 *   <li>MediaMTX mất cấu hình path tạo qua API khi khởi động lại → thêm lại path thiếu mỗi 60 giây.</li>
 *   <li>Gateway theo dõi kết nối các camera đã đăng ký (US-04.1) → gửi lại danh sách khi khởi động và khi đổi.</li>
 * </ul>
 * Chạy cho mọi Tổ chức (tác vụ hệ thống), mỗi Tổ chức trong ngữ cảnh RLS của chính nó.
 */
@Service
public class DeviceSync {

    private static final Logger log = LoggerFactory.getLogger(DeviceSync.class);
    static final int MONITOR_INTERVAL_SECONDS = 30;

    private final CameraRepository cameras;
    private final CameraService cameraService;
    private final MediaMtxClient mediamtx;
    private final GatewayMonitorClient monitor;
    private final TunnelRouting routing;
    private final vn.camhub.device.infrastructure.RecorderRepository recorders;
    private final vn.camhub.platform.crypto.CryptoService crypto;
    private final DeviceFacade devices;

    public DeviceSync(CameraRepository cameras, CameraService cameraService, MediaMtxClient mediamtx,
            GatewayMonitorClient monitor, TunnelRouting routing,
            vn.camhub.device.infrastructure.RecorderRepository recorders,
            vn.camhub.platform.crypto.CryptoService crypto, DeviceFacade devices) {
        this.crypto = crypto;
        this.devices = devices;
        this.routing = routing;
        this.recorders = recorders;
        this.cameras = cameras;
        this.cameraService = cameraService;
        this.mediamtx = mediamtx;
        this.monitor = monitor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        // Lỗi đồng bộ không được làm Core không khởi động được; lần định kỳ sau sẽ thử lại
        try {
            syncMediaPaths();
            syncMonitorTargets();
            syncEventSubscriptions();
        } catch (RuntimeException e) {
            log.warn("Đồng bộ thiết bị lúc khởi động lỗi: {}", e.toString());
        }
    }

    @Async
    @EventListener
    public void onCameraChanged(CameraChanged e) {
        if (e.kind() == CameraChanged.Kind.ADDED || e.kind() == CameraChanged.Kind.REMOVED) {
            syncMonitorTargets();
            syncEventSubscriptions();
        }
    }

    @Scheduled(fixedDelayString = "${camhub.device.sync-interval:60s}", initialDelayString = "${camhub.device.sync-interval:60s}")
    public void periodic() {
        syncMediaPaths();
    }

    /** Gateway chỉ giữ danh sách theo dõi trong bộ nhớ: gửi lại định kỳ phòng khi Gateway khởi động lại. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void periodicMonitor() {
        syncMonitorTargets();
        syncEventSubscriptions();
    }

    /**
     * Đăng ký sự kiện (PullPoint) cho camera ONVIF báo hỗ trợ sự kiện (US-36: motionSource = DEVICE); hủy đăng ký
     * camera đã xóa. Gateway tự gia hạn đăng ký và phát MotionDetected lên RabbitMQ.
     */
    public void syncEventSubscriptions() {
        Set<String> current;
        try {
            current = monitor.subscribedCameras();
        } catch (RuntimeException e) {
            log.warn("Không lấy được danh sách đăng ký sự kiện từ Device Gateway: {}", e.toString());
            return;
        }
        Set<String> wanted = new HashSet<>();
        for (UUID org : cameras.organizations()) {
            OrganizationContext.runAs(org, () -> cameras.all().stream()
                    .filter(c -> "ONVIF".equals(c.protocol()) && c.recorderId() == null && hasEvents(c))
                    .forEach(c -> {
                        wanted.add(c.id().toString());
                        if (!current.contains(c.id().toString())) {
                            try {
                                String pwd = cameras.passwordEnc(c.id()).map(crypto::decrypt).orElse(null);
                                monitor.subscribe(c.id().toString(), vn.camhub.device.infrastructure.GatewayClient
                                        .address(new vn.camhub.device.api.DeviceControlPort.DeviceAddress(c.ipAddress(),
                                                c.port() == null ? 80 : c.port(), c.username(), pwd,
                                                routing.proxy(c.connectorId()))));
                            } catch (RuntimeException ex) {
                                log.warn("Không đăng ký được sự kiện cho camera {}: {}", c.id(), ex.toString());
                            }
                        }
                    }));
        }
        current.stream().filter(id -> !wanted.contains(id)).forEach(id -> {
            try {
                monitor.unsubscribe(id);
            } catch (RuntimeException ex) {
                log.debug("Không hủy được đăng ký sự kiện {}: {}", id, ex.toString());
            }
        });
    }

    /** US-25 AC2: đo độ lệch đồng hồ camera ONVIF mỗi giờ (giao diện đánh dấu lệch > 5 giây). */
    @Scheduled(cron = "${camhub.device.clock-check-cron:0 17 * * * *}")
    public void checkClocks() {
        for (UUID org : cameras.organizations()) {
            OrganizationContext.runAs(org, () -> cameras.all().stream()
                    .filter(c -> "ONVIF".equals(c.protocol()) && c.recorderId() == null && "ONLINE".equals(c.status()))
                    .forEach(c -> {
                        try {
                            devices.network(c.id());
                        } catch (RuntimeException e) {
                            log.debug("Không đo được đồng hồ camera {}: {}", c.id(), e.toString());
                        }
                    }));
        }
    }

    private static boolean hasEvents(Camera c) {
        return c.capabilities() != null && c.capabilities().replace(" ", "").contains("\"events\":true");
    }

    /** Thêm path còn thiếu trên MediaMTX (không xóa path lạ: có thể là path tạm của màn hình kiểm tra kết nối). */
    public void syncMediaPaths() {
        Set<String> existing;
        try {
            existing = new HashSet<>(mediamtx.configuredPaths());
        } catch (RuntimeException e) {
            log.warn("MediaMTX chưa sẵn sàng, đồng bộ path sau: {}", e.toString());
            return;
        }
        int added = 0;
        for (UUID org : cameras.organizations()) {
            added += OrganizationContext.callAs(org, () -> {
                int n = 0;
                for (Camera c : cameras.all()) {
                    boolean subMissing = !existing.contains(c.subPath())
                            && cameras.streams(c.id()).stream().anyMatch(st -> "SUB".equals(st.type()));
                    if (!existing.contains(c.mediaPath()) || subMissing) {
                        try {
                            cameraService.syncPaths(c);
                            n++;
                        } catch (RuntimeException e) {
                            log.warn("Không tạo được path MediaMTX cho camera {}: {}", c.id(), e.toString());
                        }
                    }
                }
                return n;
            });
        }
        if (added > 0) {
            log.info("Đã đồng bộ {} camera lên MediaMTX", added);
        }
    }

    /** Gửi toàn bộ danh sách camera cần theo dõi kết nối cho Device Gateway. */
    public void syncMonitorTargets() {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (UUID org : cameras.organizations()) {
            OrganizationContext.runAs(org, () -> {
                // Kênh của đầu ghi không theo dõi riêng: theo dõi một lần ở đầu ghi (US-03 AC2)
                cameras.all().stream().filter(c -> c.recorderId() == null).forEach(c -> targets.add(target(c)));
                recorders.all().forEach(r -> targets.add(recorderTarget(r)));
            });
        }
        try {
            monitor.replaceTargets(targets, MONITOR_INTERVAL_SECONDS);
        } catch (RuntimeException e) {
            log.warn("Không gửi được danh sách theo dõi cho Device Gateway: {}", e.toString());
        }
    }

    /** Đầu ghi theo dõi như một camera ONVIF; sự kiện mang id đầu ghi, CameraStatusService áp cho mọi kênh. */
    Map<String, Object> recorderTarget(vn.camhub.device.infrastructure.RecorderRepository.Recorder r) {
        Map<String, Object> t = new java.util.HashMap<>();
        t.put("cameraId", r.id().toString());
        t.put("protocol", "ONVIF");
        t.put("host", r.host());
        t.put("port", r.port());
        t.put("rtspHost", null);
        t.put("rtspPort", null);
        t.put("proxy", routing.proxy(r.connectorId()));
        return t;
    }

    Map<String, Object> target(Camera c) {
        Map<String, Object> t = new java.util.HashMap<>();
        t.put("proxy", routing.proxy(c.connectorId()));
        t.put("cameraId", c.id().toString());
        t.put("protocol", c.protocol());
        t.put("host", c.ipAddress());
        t.put("port", c.port() == null ? 80 : c.port());
        t.put("rtspHost", null);
        t.put("rtspPort", null);
        if ("RTSP".equals(c.protocol()) && c.streamUrl() != null) {
            try {
                var u = RtspUrls.parse(c.streamUrl());
                if (c.connectorId() != null) {
                    // RTSP sau NAT: kiểm tra cổng chuyển tiếp trên Tunnel Server
                    var hp = routing.forward(c.connectorId(), u.host(), u.port());
                    t.put("rtspHost", hp.host());
                    t.put("rtspPort", hp.port());
                } else {
                    t.put("rtspHost", u.host());
                    t.put("rtspPort", u.port());
                }
            } catch (IllegalArgumentException e) {
                log.warn("Camera {} có URL RTSP không hợp lệ, bỏ qua khi theo dõi kết nối", c.id());
            }
        }
        return t;
    }
}
