package vn.camhub.device.application;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.device.application.CameraService.ConnectionRequest;
import vn.camhub.device.application.CameraService.CreateRequest;
import vn.camhub.device.application.CameraService.Protocol;
import vn.camhub.device.domain.RtspUrls;
import vn.camhub.device.infrastructure.SpreadsheetReader;
import vn.camhub.organization.api.OrganizationApi;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;

/**
 * Nhập camera từ tệp .csv/.xlsx (US-07, UC-13): xem trước từng dòng kèm lỗi, rồi nhập các dòng hợp lệ – dòng lỗi bị
 * bỏ qua và báo cáo. Cột (theo tiêu đề, không phân biệt dấu/hoa thường): Tên, Địa điểm, Khu vực, Giao thức
 * (ONVIF/RTSP), Địa chỉ IP, Cổng, Tài khoản, Mật khẩu, URL RTSP.
 */
@Service
public class CameraImportService {

    public record Row(int line, String name, String site, String zone, String protocol, String host, Integer port,
            String username, String password, String rtspUrl, List<String> errors) {

        /** Mật khẩu không gửi lại trình duyệt khi xem trước. */
        public Row masked() {
            return new Row(line, name, site, zone, protocol, host, port, username, password == null ? null : "••••",
                    rtspUrl, errors);
        }
    }

    public record Preview(int total, int valid, List<Row> rows) {
    }

    public record ImportResult(int line, String name, boolean ok, UUID cameraId, String code, String message) {
    }

    /** Tiêu đề cột chấp nhận (đã bỏ dấu, chữ thường) → trường. */
    private static final Map<String, String> HEADERS = Map.ofEntries(
            Map.entry("ten", "name"), Map.entry("name", "name"), Map.entry("ten camera", "name"),
            Map.entry("dia diem", "site"), Map.entry("site", "site"),
            Map.entry("khu vuc", "zone"), Map.entry("zone", "zone"),
            Map.entry("giao thuc", "protocol"), Map.entry("protocol", "protocol"),
            Map.entry("dia chi ip", "host"), Map.entry("ip", "host"), Map.entry("host", "host"),
            Map.entry("cong", "port"), Map.entry("port", "port"),
            Map.entry("tai khoan", "username"), Map.entry("username", "username"),
            Map.entry("mat khau", "password"), Map.entry("password", "password"),
            Map.entry("url rtsp", "rtspUrl"), Map.entry("rtsp url", "rtspUrl"), Map.entry("rtsp", "rtspUrl"));

    private final CameraService cameras;
    private final OrganizationApi organizations;
    private final AccessApi access;
    private final ApplicationEventPublisher events;

    public CameraImportService(CameraService cameras, OrganizationApi organizations, AccessApi access,
            ApplicationEventPublisher events) {
        this.cameras = cameras;
        this.organizations = organizations;
        this.access = access;
        this.events = events;
    }

    public Preview preview(UUID actor, String fileName, byte[] content) {
        List<Row> rows = parse(actor, fileName, content);
        return new Preview(rows.size(), (int) rows.stream().filter(r -> r.errors().isEmpty()).count(),
                rows.stream().map(Row::masked).toList());
    }

    /** Nhập các dòng hợp lệ của tệp; dòng lỗi (kể cả lỗi kết nối) bị bỏ qua và báo cáo. */
    public List<ImportResult> importFile(UUID actor, String fileName, byte[] content, boolean saveUnverified) {
        List<ImportResult> out = new ArrayList<>();
        for (Row r : parse(actor, fileName, content)) {
            if (!r.errors().isEmpty()) {
                out.add(new ImportResult(r.line(), r.name(), false, null, "INVALID_ROW", String.join("; ", r.errors())));
                continue;
            }
            UUID zone = organizations.zoneByName(r.site(), r.zone()).orElseThrow();
            Protocol p = Protocol.valueOf(r.protocol());
            try {
                var cam = cameras.create(actor, new CreateRequest(zone, r.name(), new ConnectionRequest(p,
                        p == Protocol.ONVIF ? r.host() : null, r.port(), r.username(), r.password(),
                        p == Protocol.RTSP ? r.rtspUrl() : null, null), saveUnverified));
                out.add(new ImportResult(r.line(), r.name(), true, cam.id(), null, null));
            } catch (ApiException e) {
                out.add(new ImportResult(r.line(), r.name(), false, null, e.code(), e.getMessage()));
            }
        }
        long ok = out.stream().filter(ImportResult::ok).count();
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "CAMERA", "import", Map.of(
                "operation", "IMPORT_CAMERAS", "file", fileName == null ? "" : fileName, "imported", ok,
                "skipped", out.size() - ok)));
        return out;
    }

    private List<Row> parse(UUID actor, String fileName, byte[] content) {
        if (!access.hasAnywhere(actor, Permission.CONFIGURE_CAMERA)) {
            throw ApiException.forbidden();
        }
        List<List<String>> table;
        try {
            table = SpreadsheetReader.read(fileName, content);
        } catch (IOException e) {
            throw ApiException.badRequest("INVALID_FILE", e.getMessage());
        }
        if (table.isEmpty()) {
            throw ApiException.badRequest("INVALID_FILE", "Tệp không có dữ liệu");
        }
        Map<String, Integer> col = new HashMap<>();
        List<String> header = table.get(0);
        for (int i = 0; i < header.size(); i++) {
            String field = HEADERS.get(normalize(header.get(i)));
            if (field != null) {
                col.putIfAbsent(field, i);
            }
        }
        if (!col.containsKey("name") || !col.containsKey("site") || !col.containsKey("zone")) {
            throw ApiException.badRequest("INVALID_FILE", "Thiếu cột bắt buộc: Tên, Địa điểm, Khu vực");
        }
        List<Row> rows = new ArrayList<>();
        Set<String> seenHosts = new HashSet<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> cells = table.get(i);
            rows.add(validate(actor, i + 1, cells, col, seenHosts));
        }
        return rows;
    }

    private Row validate(UUID actor, int line, List<String> cells, Map<String, Integer> col, Set<String> seenHosts) {
        String name = cell(cells, col, "name");
        String site = cell(cells, col, "site");
        String zone = cell(cells, col, "zone");
        String protocol = cell(cells, col, "protocol");
        String host = cell(cells, col, "host");
        String portText = cell(cells, col, "port");
        String rtsp = cell(cells, col, "rtspUrl");
        List<String> errors = new ArrayList<>();
        if (name == null) {
            errors.add("Thiếu tên camera");
        }
        protocol = protocol == null ? (rtsp != null ? "RTSP" : "ONVIF") : protocol.toUpperCase(Locale.ROOT);
        if (!protocol.equals("ONVIF") && !protocol.equals("RTSP")) {
            errors.add("Giao thức phải là ONVIF hoặc RTSP");
        }
        var zoneId = organizations.zoneByName(site, zone);
        if (zoneId.isEmpty()) {
            errors.add("Không tìm thấy khu vực \"" + zone + "\" của địa điểm \"" + site + "\"");
        } else if (!organizations.siteOfZone(zoneId.get())
                .map(s -> access.canOnSite(actor, Permission.CONFIGURE_CAMERA, s)).orElse(false)) {
            errors.add("Không có quyền thêm camera vào địa điểm này");
        }
        Integer port = null;
        if (portText != null) {
            try {
                port = Integer.parseInt(portText.trim());
                if (port < 1 || port > 65535) {
                    errors.add("Cổng không hợp lệ");
                }
            } catch (NumberFormatException e) {
                errors.add("Cổng không hợp lệ");
            }
        }
        if ("ONVIF".equals(protocol)) {
            if (host == null) {
                errors.add("Thiếu địa chỉ IP");
            } else if (!seenHosts.add(host.toLowerCase(Locale.ROOT) + ":" + (port == null ? 80 : port))) {
                errors.add("Trùng địa chỉ với dòng khác trong tệp");
            }
        }
        if ("RTSP".equals(protocol)) {
            if (rtsp == null) {
                errors.add("Thiếu URL RTSP");
            } else {
                try {
                    RtspUrls.parse(rtsp);
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                }
            }
        }
        return new Row(line, name, site, zone, protocol, host, port, cell(cells, col, "username"),
                cell(cells, col, "password"), rtsp, errors);
    }

    private static String cell(List<String> cells, Map<String, Integer> col, String field) {
        Integer i = col.get(field);
        if (i == null || i >= cells.size()) {
            return null;
        }
        String v = cells.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s == null ? "" : s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd');
        return n.replaceAll("\\s+", " ");
    }
}
