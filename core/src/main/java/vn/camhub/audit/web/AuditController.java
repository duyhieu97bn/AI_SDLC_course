package vn.camhub.audit.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.audit.infrastructure.AuditRepository;
import vn.camhub.audit.infrastructure.AuditRepository.Entry;
import vn.camhub.audit.infrastructure.AuditRepository.Filter;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.security.CurrentUser;

/** GET /audit-logs và /audit-logs/export (US-33, UC-08). Chỉ Admin (MANAGE_SYSTEM toàn Tổ chức). */
@RestController
@RequestMapping("/api/v1/audit-logs")
@PreAuthorize("hasPermission(null, 'ORGANIZATION', 'MANAGE_SYSTEM')")
public class AuditController {

    public record PageView(List<Entry> items, long total, int page, int size) {
    }

    private final AuditRepository repo;
    private final ApplicationEventPublisher events;

    public AuditController(AuditRepository repo, ApplicationEventPublisher events) {
        this.repo = repo;
        this.events = events;
    }

    @GetMapping
    public PageView search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String action, @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String targetId, @RequestParam(required = false) String result,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Filter f = new Filter(from, to, blank(action), actorId, blank(targetId), blank(result), q);
        int s = Math.min(Math.max(size, 1), 500);
        return new PageView(repo.search(f, Math.max(page, 0), s), repo.count(f), page, s);
    }

    /** Xuất CSV (UTF-8 có BOM để Excel đọc đúng tiếng Việt); bản thân việc xuất cũng được ghi nhật ký. */
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String action, @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String targetId, @RequestParam(required = false) String result,
            @RequestParam(required = false) String q, HttpServletResponse res) throws IOException {
        Filter f = new Filter(from, to, blank(action), actorId, blank(targetId), blank(result), q);
        events.publishEvent(AuditableEvent.of(CurrentUser.require().userId(), "EXPORT_AUDIT", "AUDIT_LOG", null,
                Map.of("from", String.valueOf(from), "to", String.valueOf(to), "action", String.valueOf(action))));
        res.setContentType("text/csv; charset=UTF-8");
        res.setHeader("Content-Disposition", "attachment; filename=\"camhub-audit-" + Instant.now().getEpochSecond()
                + ".csv\"");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        PrintWriter w = res.getWriter();
        w.write('﻿');
        w.println("occurred_at,actor,action,target_type,target_id,target_name,ip,result,details");
        repo.forEach(f, e -> w.println(String.join(",", csv(e.occurredAt().toString()), csv(e.actorName()),
                csv(e.action()), csv(e.targetType()), csv(e.targetId()), csv(e.targetName()), csv(e.ipAddress()),
                csv(e.result()), csv(e.details()))));
        w.flush();
    }

    static String csv(String s) {
        if (s == null) {
            return "";
        }
        // Chặn CSV injection: ô bắt đầu bằng = + - @ bị Excel hiểu là công thức
        String v = s.matches("^[=+\\-@].*") ? "'" + s : s;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
