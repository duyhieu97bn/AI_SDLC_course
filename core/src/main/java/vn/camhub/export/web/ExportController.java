package vn.camhub.export.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.camhub.export.application.ExportService;
import vn.camhub.export.application.ExportService.Download;
import vn.camhub.export.application.ExportService.DownloadLink;
import vn.camhub.export.application.ExportService.ExportView;
import vn.camhub.platform.security.CurrentUser;

/** Xuất clip (US-20) – SADD 3.7.1 nhóm "Xuất video". */
@RestController
public class ExportController {

    public record ExportBody(@NotNull UUID cameraId, @NotNull Instant startAt, @NotNull Instant endAt) {
    }

    private final ExportService exports;

    public ExportController(ExportService exports) {
        this.exports = exports;
    }

    @PostMapping("/api/v1/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExportView create(@Valid @RequestBody ExportBody body) {
        return exports.request(me(), body.cameraId(), body.startAt(), body.endAt());
    }

    @GetMapping("/api/v1/exports")
    public List<ExportView> mine() {
        return exports.mine(me());
    }

    @PostMapping("/api/v1/exports/{id}/download-link")
    public DownloadLink link(@PathVariable UUID id) {
        return exports.link(me(), id);
    }

    /** Liên kết đã ký 5 phút; công khai (không có access token) vì trình duyệt tải trực tiếp. */
    @GetMapping("/api/v1/exports/{id}/file")
    public ResponseEntity<StreamingResponseBody> file(@PathVariable UUID id, @RequestParam UUID org,
            @RequestParam long exp, @RequestParam String sig) {
        Download d = exports.open(org, id, exp, sig);
        StreamingResponseBody body = (OutputStream out) -> {
            try (InputStream in = d.content()) {
                in.transferTo(out);
            }
        };
        ResponseEntity.BodyBuilder res = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (d.size() >= 0) {
            res.contentLength(d.size());
        }
        return res.body(body);
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}
