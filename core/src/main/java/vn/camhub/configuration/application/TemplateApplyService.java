package vn.camhub.configuration.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.configuration.application.StreamConfigService.ApplyResult;
import vn.camhub.configuration.domain.ConfigTemplate;
import vn.camhub.configuration.domain.ConfigTemplate.Detail;
import vn.camhub.configuration.domain.ConfigTemplate.ImageSpec;
import vn.camhub.configuration.domain.ConfigTemplate.StreamSpec;
import vn.camhub.configuration.infrastructure.TemplateRepository;
import vn.camhub.configuration.infrastructure.TemplateRepository.Application;
import vn.camhub.device.api.DeviceApi;
import vn.camhub.device.api.DeviceApi.CameraMedia;
import vn.camhub.device.api.DeviceControlPort;
import vn.camhub.device.api.DeviceControlPort.EncoderChange;
import vn.camhub.device.api.DeviceControlPort.EncoderConfig;
import vn.camhub.device.api.DeviceControlPort.ImagingSettings;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.ApiException;
import vn.camhub.platform.tenancy.OrganizationContext;
import vn.camhub.recording.api.RecordingApi;

/**
 * Cấu hình mẫu (US-27, UC-42): quản lý mẫu và áp song song cho nhiều camera. Mỗi camera: chính sách ghi, luồng chính,
 * hình ảnh; tham số camera không hỗ trợ được bỏ qua (PARTIAL), lỗi thiết bị → FAILED; có thể "Áp lại" riêng camera lỗi.
 */
@Service
public class TemplateApplyService {

    static final int MAX_CAMERAS = 200;

    private final TemplateRepository repo;
    private final DeviceApi devices;
    private final AccessApi access;
    private final RecordingApi recordings;
    private final StreamConfigService streams;
    private final ImageConfigService images;
    private final ApplicationEventPublisher events;

    public TemplateApplyService(TemplateRepository repo, DeviceApi devices, AccessApi access, RecordingApi recordings,
            StreamConfigService streams, ImageConfigService images, ApplicationEventPublisher events) {
        this.repo = repo;
        this.devices = devices;
        this.access = access;
        this.recordings = recordings;
        this.streams = streams;
        this.images = images;
        this.events = events;
    }

    // ---------------------------------------------------------------- mẫu

    public List<ConfigTemplate> list(UUID actor) {
        require(actor);
        return repo.all();
    }

    public ConfigTemplate save(UUID actor, ConfigTemplate t) {
        require(actor);
        if (t.name() == null || t.name().isBlank() || t.empty()) {
            throw ApiException.badRequest("INVALID_TEMPLATE", "Nhập tên mẫu và ít nhất một phần cấu hình");
        }
        ConfigTemplate saved = new ConfigTemplate(t.id() == null ? UUID.randomUUID() : t.id(), t.name().trim(),
                t.description(), t.recording(), t.stream(), t.image());
        try {
            repo.save(saved, actor);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(409, "DUPLICATE", "Đã có mẫu tên \"" + saved.name() + "\"");
        }
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "TEMPLATE", saved.id().toString(),
                Map.of("operation", "SAVE_TEMPLATE", "targetName", saved.name())));
        return repo.find(saved.id()).orElseThrow();
    }

    public void delete(UUID actor, UUID id) {
        require(actor);
        if (!repo.delete(id)) {
            throw ApiException.notFound("mẫu");
        }
    }

    // ---------------------------------------------------------------- áp mẫu

    public Application apply(UUID actor, UUID templateId, List<UUID> cameraIds) {
        require(actor);
        ConfigTemplate t = repo.find(templateId).orElseThrow(() -> ApiException.notFound("mẫu"));
        if (cameraIds == null || cameraIds.isEmpty() || cameraIds.size() > MAX_CAMERAS) {
            throw ApiException.badRequest("INVALID_SELECTION", "Chọn từ 1 đến " + MAX_CAMERAS + " camera");
        }
        UUID application = repo.startApplication(templateId, actor);
        UUID org = OrganizationContext.require();
        // Song song theo camera (luồng ảo): một camera chậm/mất kết nối không giữ các camera khác
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> jobs = new ArrayList<>();
            for (UUID cam : cameraIds.stream().distinct().toList()) {
                jobs.add(pool.submit(() -> OrganizationContext.runAs(org, () -> applyOne(actor, application, t, cam))));
            }
            for (Future<?> f : jobs) {
                try {
                    f.get();
                } catch (Exception e) {
                    // lỗi từng camera đã ghi vào kết quả
                }
            }
        }
        repo.finish(application);
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "TEMPLATE", templateId.toString(), Map.of(
                "operation", "APPLY_TEMPLATE", "targetName", t.name(), "cameras", cameraIds.size())));
        return repo.application(application).orElseThrow();
    }

    /** "Áp lại" (US-27 AC1): áp lại mẫu cho các camera thất bại của lần áp trước. */
    public Application reapplyFailed(UUID actor, UUID applicationId) {
        Application a = repo.application(applicationId).orElseThrow(() -> ApiException.notFound("lần áp"));
        List<UUID> failed = a.results().stream().filter(r -> "FAILED".equals(r.result()))
                .map(TemplateRepository.ResultRow::cameraId).toList();
        if (failed.isEmpty()) {
            throw ApiException.badRequest("NOTHING_FAILED", "Không có camera thất bại để áp lại");
        }
        return apply(actor, a.templateId(), failed);
    }

    public Application application(UUID actor, UUID id) {
        require(actor);
        return repo.application(id).orElseThrow(() -> ApiException.notFound("lần áp"));
    }

    private void applyOne(UUID actor, UUID application, ConfigTemplate t, UUID cameraId) {
        CameraMedia cam = devices.media(cameraId).orElse(null);
        String name = cam == null ? cameraId.toString() : cam.name();
        List<Detail> details = new ArrayList<>();
        if (cam == null || !access.canOnCamera(actor, Permission.CONFIGURE_CAMERA, cameraId)) {
            details.add(Detail.error("CAMERA", null, cam == null ? "Không tìm thấy camera" : "Không có quyền cấu hình"));
        } else {
            if (t.recording() != null) {
                details.add(recording(actor, cameraId, t));
            }
            if (t.stream() != null) {
                details.addAll(cam.onvif() ? stream(actor, cameraId, t.stream())
                        : List.of(Detail.skipped("STREAM", null, "Camera RTSP không cấu hình luồng qua ONVIF")));
            }
            if (t.image() != null) {
                details.addAll(cam.onvif() ? image(actor, cameraId, t.image())
                        : List.of(Detail.skipped("IMAGE", null, "Camera RTSP không cấu hình hình ảnh qua ONVIF")));
            }
        }
        repo.saveResult(application, cameraId, name, ConfigTemplate.summarize(details).name(), details);
    }

    private Detail recording(UUID actor, UUID cameraId, ConfigTemplate t) {
        try {
            var r = t.recording();
            recordings.applyPolicy(actor, cameraId, new RecordingApi.PolicySpec(r.mode(),
                    r.schedule() == null ? List.of() : r.schedule().stream()
                            .map(s -> new RecordingApi.SlotSpec(s.day(), s.from(), s.to())).toList(),
                    r.preEventSeconds(), r.postEventSeconds()));
            return Detail.ok("RECORDING", "mode");
        } catch (RuntimeException e) {
            return Detail.error("RECORDING", "mode", e.getMessage());
        }
    }

    /** Luồng chính = cấu hình encoder độ phân giải lớn nhất; tham số ngoài khả năng camera thì bỏ qua (US-29). */
    private List<Detail> stream(UUID actor, UUID cameraId, StreamSpec s) {
        List<Detail> out = new ArrayList<>();
        List<EncoderConfig> encoders;
        try {
            encoders = streams.get(actor, cameraId);
        } catch (RuntimeException e) {
            return List.of(Detail.error("STREAM", null, e.getMessage()));
        }
        EncoderConfig main = encoders.stream().max(Comparator.comparingInt(e -> e.width() * e.height())).orElse(null);
        if (main == null) {
            return List.of(Detail.skipped("STREAM", null, "Camera không có cấu hình encoder"));
        }
        var o = main.options();
        String codec = null;
        Integer w = null;
        Integer h = null;
        Integer fps = null;
        Integer bitrate = null;
        Integer gov = null;
        if (s.codec() != null) {
            if (o == null || o.codecs() == null || o.codecs().contains(s.codec())) {
                codec = s.codec();
            } else {
                out.add(Detail.skipped("STREAM", "codec", "Camera không hỗ trợ " + s.codec()));
            }
        }
        if (s.width() != null && s.height() != null) {
            if (o == null || o.resolutions() == null || o.resolutions().stream()
                    .anyMatch(r -> r.width() == s.width() && r.height() == s.height())) {
                w = s.width();
                h = s.height();
            } else {
                out.add(Detail.skipped("STREAM", "resolution", "Camera không hỗ trợ " + s.width() + "×" + s.height()));
            }
        }
        fps = within("fps", s.fps(), o == null ? null : o.fpsRange(), out);
        bitrate = within("bitrateKbps", s.bitrateKbps(), o == null ? null : o.bitrateRange(), out);
        gov = within("govLength", s.govLength(), o == null ? null : o.govLengthRange(), out);
        if (codec == null && w == null && fps == null && bitrate == null && gov == null) {
            return out;
        }
        try {
            ApplyResult r = streams.apply(actor, cameraId, List.of(new EncoderChange(main.token(), codec, w, h, fps,
                    bitrate, gov)));
            r.parameters().forEach(p -> out.add(p.ok() ? Detail.ok("STREAM", p.parameter())
                    : Detail.error("STREAM", p.parameter(), p.message())));
        } catch (RuntimeException e) {
            out.add(Detail.error("STREAM", null, e.getMessage()));
        }
        return out;
    }

    private static Integer within(String param, Integer v, DeviceControlPort.IntRange range, List<Detail> out) {
        if (v == null) {
            return null;
        }
        if (range == null || (v >= range.min() && v <= range.max())) {
            return v;
        }
        out.add(Detail.skipped("STREAM", param, "Ngoài khoảng " + range.min() + "–" + range.max()));
        return null;
    }

    private List<Detail> image(UUID actor, UUID cameraId, ImageSpec s) {
        List<Detail> out = new ArrayList<>();
        ImagingSettings cur;
        try {
            cur = images.get(actor, cameraId);
        } catch (RuntimeException e) {
            return List.of(Detail.error("IMAGE", null, e.getMessage()));
        }
        var o = cur.options();
        Double b = s.brightness();
        if (b != null && (o == null || o.brightness() == null || cur.brightness() == null)) {
            out.add(Detail.skipped("IMAGE", "brightness", "Camera không hỗ trợ chỉnh độ sáng"));
            b = null;
        }
        Double c = s.contrast();
        if (c != null && (o == null || o.contrast() == null || cur.contrast() == null)) {
            out.add(Detail.skipped("IMAGE", "contrast", "Camera không hỗ trợ chỉnh tương phản"));
            c = null;
        }
        String ir = s.irMode();
        if (ir != null && (o == null || o.irModes() == null || !o.irModes().contains(ir))) {
            out.add(Detail.skipped("IMAGE", "irMode", "Camera không hỗ trợ chế độ hồng ngoại " + ir));
            ir = null;
        }
        Boolean wdr = s.wdr();
        if (Boolean.TRUE.equals(wdr) && (o == null || !o.wdr())) {
            out.add(Detail.skipped("IMAGE", "wdr", "Camera không hỗ trợ WDR")); // US-27 AC1: một phần
            wdr = null;
        }
        if (b == null && c == null && ir == null && wdr == null) {
            return out;
        }
        try {
            images.apply(actor, cameraId, new ImageConfigService.ImageChange(null, b, c, ir, wdr));
            if (b != null) {
                out.add(Detail.ok("IMAGE", "brightness"));
            }
            if (c != null) {
                out.add(Detail.ok("IMAGE", "contrast"));
            }
            if (ir != null) {
                out.add(Detail.ok("IMAGE", "irMode"));
            }
            if (wdr != null) {
                out.add(Detail.ok("IMAGE", "wdr"));
            }
        } catch (RuntimeException e) {
            out.add(Detail.error("IMAGE", null, e.getMessage()));
        }
        return out;
    }

    private void require(UUID actor) {
        if (!access.hasAnywhere(actor, Permission.CONFIGURE_CAMERA)) {
            throw ApiException.forbidden();
        }
    }
}
