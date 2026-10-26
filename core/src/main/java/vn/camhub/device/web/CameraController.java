package vn.camhub.device.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.application.CameraService;
import vn.camhub.device.application.CameraService.BulkDevice;
import vn.camhub.device.application.CameraService.BulkResult;
import vn.camhub.device.application.CameraImportService;
import vn.camhub.device.application.CameraService.ConnectionRequest;
import org.springframework.web.multipart.MultipartFile;
import vn.camhub.device.application.CameraService.ConnectionResult;
import vn.camhub.device.application.CameraService.CreateRequest;
import vn.camhub.device.application.DiscoveryService;
import vn.camhub.device.domain.Camera;
import vn.camhub.device.domain.StreamInfo;
import vn.camhub.device.infrastructure.CameraRepository;
import vn.camhub.device.infrastructure.CameraRepository.Query;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.security.CurrentUser;

/**
 * Camera (US-01, US-02, US-04, US-05). Danh sách chỉ gồm camera trong phạm vi quyền (US-32 AC1). Không trả URL
 * hay mật khẩu camera ra trình duyệt (SADD 4).
 */
@RestController
@RequestMapping("/api/v1")
public class CameraController {

    public record CameraView(UUID id, String name, UUID siteId, UUID zoneId, Set<UUID> groupIds, String protocol,
            String ipAddress, Integer port, String manufacturer, String model, String macAddress, String firmware,
            String status, Instant statusChangedAt, Instant lastOfflineAt, boolean verified, JsonNode capabilities,
            Instant createdAt, Double clockDriftSeconds, UUID recorderId, UUID connectorId) {
    }

    public record StreamView(String type, String codec, Integer width, Integer height, Integer fps,
            Integer bitrateKbps, boolean bFrames) {
        static StreamView of(StreamInfo s) {
            return new StreamView(s.type(), s.codec(), s.width(), s.height(), s.fps(), s.bitrateKbps(), s.bFrames());
        }
    }

    public record PageView(List<CameraView> items, long total, int page, int size) {
    }

    public record ConnectionBody(@NotNull CameraService.Protocol protocol, String host, Integer port, String username,
            String password, String streamUrl, UUID siteId) {
        ConnectionRequest toRequest() {
            return new ConnectionRequest(protocol, host, port, username, password, streamUrl, siteId);
        }
    }

    public record CreateBody(@NotNull UUID zoneId, @Size(max = 200) String name, @NotNull @Valid ConnectionBody connection,
            boolean saveUnverified) {
    }

    public record BulkBody(@NotNull UUID zoneId, String username, String password, @NotNull List<BulkDevice> devices) {
    }

    public record RenameBody(@NotBlank @Size(max = 200) String name) {
    }

    public record DiscoveryBody(List<String> targets) {
    }

    static final Set<Permission> LIST = Set.of(Permission.VIEW_LIVE, Permission.VIEW_PLAYBACK,
            Permission.CONFIGURE_CAMERA);

    private final CameraRepository cameras;
    private final CameraService service;
    private final DiscoveryService discovery;
    private final CameraImportService imports;
    private final AccessApi access;
    private final ObjectMapper json;

    public CameraController(CameraImportService imports, CameraRepository cameras, CameraService service,
            DiscoveryService discovery,
            AccessApi access, ObjectMapper json) {
        this.cameras = cameras;
        this.service = service;
        this.discovery = discovery;
        this.imports = imports;
        this.access = access;
        this.json = json;
    }

    @GetMapping("/cameras")
    public PageView list(@RequestParam(required = false) UUID siteId, @RequestParam(required = false) UUID zoneId,
            @RequestParam(required = false) UUID groupId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int s = Math.min(Math.max(size, 1), 1000);
        int p = Math.max(page, 0);
        var scope = access.scopeFilter(me(), LIST);
        var result = cameras.search(scope, new Query(siteId, zoneId, groupId, status, q, p, s));
        var drifts = cameras.clockDrifts();
        return new PageView(result.items().stream().map(c -> view(c, drifts)).toList(), result.total(), p, s);
    }

    @GetMapping("/cameras/{id}")
    @PreAuthorize("hasPermission(#id, 'CAMERA', 'VIEW_LIVE') or hasPermission(#id, 'CAMERA', 'VIEW_PLAYBACK')"
            + " or hasPermission(#id, 'CAMERA', 'CONFIGURE_CAMERA')")
    public CameraView get(@PathVariable UUID id) {
        return cameras.find(id).map(this::view).orElseThrow(() -> ApiException.notFound("camera"));
    }

    @GetMapping("/cameras/{id}/streams")
    @PreAuthorize("hasPermission(#id, 'CAMERA', 'VIEW_LIVE') or hasPermission(#id, 'CAMERA', 'CONFIGURE_CAMERA')")
    public List<StreamView> streams(@PathVariable UUID id) {
        return cameras.streams(id).stream().map(StreamView::of).toList();
    }

    /** Kiểm tra kết nối trước khi lưu (US-02 AC1): trả thông tin thiết bị và URL xem trước 60 giây. */
    @PostMapping("/cameras/test-connection")
    public ConnectionResult test(@Valid @RequestBody ConnectionBody body) {
        return service.test(me(), body.toRequest());
    }

    @PostMapping("/cameras")
    @ResponseStatus(HttpStatus.CREATED)
    public CameraView create(@Valid @RequestBody CreateBody body) {
        return view(service.create(me(), new CreateRequest(body.zoneId(), body.name(), body.connection().toRequest(),
                body.saveUnverified())));
    }

    /** Thêm hàng loạt camera đã dò được, dùng một tài khoản chung (US-01 AC3). */
    @PostMapping("/cameras/bulk")
    public List<BulkResult> bulk(@Valid @RequestBody BulkBody body) {
        return service.createMany(me(), body.zoneId(), body.username(), body.password(), body.devices());
    }

    @PatchMapping("/cameras/{id}")
    public CameraView rename(@PathVariable UUID id, @Valid @RequestBody RenameBody body) {
        service.rename(me(), id, body.name());
        return get(id);
    }

    @DeleteMapping("/cameras/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(me(), id);
    }

    // ---------------------------------------------------------------- nhập từ tệp (US-07)

    @PostMapping(value = "/cameras/import/preview", consumes = "multipart/form-data")
    public CameraImportService.Preview importPreview(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        return imports.preview(me(), file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping(value = "/cameras/import", consumes = "multipart/form-data")
    public List<CameraImportService.ImportResult> importFile(@RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean saveUnverified) throws java.io.IOException {
        return imports.importFile(me(), file.getOriginalFilename(), file.getBytes(), saveUnverified);
    }

    @PostMapping("/sites/{siteId}/discovery")
    public List<DiscoveryService.Found> discover(@PathVariable UUID siteId,
            @RequestBody(required = false) DiscoveryBody body) {
        return discovery.discover(me(), siteId, body == null || body.targets() == null ? List.of() : body.targets());
    }

    private CameraView view(Camera c) {
        return view(c, cameras.clockDrifts());
    }

    /** {@code drifts}: độ lệch đồng hồ đo gần nhất (US-25: giao diện đánh dấu lệch giờ > 5 giây). */
    private CameraView view(Camera c, java.util.Map<UUID, Double> drifts) {
        JsonNode caps;
        try {
            caps = json.readTree(c.capabilities() == null ? "{}" : c.capabilities());
        } catch (Exception e) {
            caps = json.createObjectNode();
        }
        return new CameraView(c.id(), c.name(), c.siteId(), c.zoneId(), c.groupIds(), c.protocol(), c.ipAddress(),
                c.port(), c.manufacturer(), c.model(), c.macAddress(), c.firmware(), c.status(), c.statusChangedAt(),
                c.lastOfflineAt(), c.verified(), caps, c.createdAt(), drifts.get(c.id()), c.recorderId(), c.connectorId());
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}
