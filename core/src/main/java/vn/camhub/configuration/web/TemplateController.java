package vn.camhub.configuration.web;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.configuration.application.TemplateApplyService;
import vn.camhub.configuration.domain.ConfigTemplate;
import vn.camhub.configuration.infrastructure.TemplateRepository.Application;
import vn.camhub.platform.security.CurrentUser;

/** Cấu hình mẫu (US-27, design/templates.html). */
@RestController
public class TemplateController {

    public record ApplyBody(List<UUID> cameraIds) {
    }

    private final TemplateApplyService templates;

    public TemplateController(TemplateApplyService templates) {
        this.templates = templates;
    }

    @GetMapping("/api/v1/config-templates")
    public List<ConfigTemplate> list() {
        return templates.list(me());
    }

    @PostMapping("/api/v1/config-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public ConfigTemplate create(@RequestBody ConfigTemplate body) {
        return templates.save(me(), new ConfigTemplate(null, body.name(), body.description(), body.recording(),
                body.stream(), body.image()));
    }

    @PutMapping("/api/v1/config-templates/{id}")
    public ConfigTemplate update(@PathVariable UUID id, @RequestBody ConfigTemplate body) {
        return templates.save(me(), new ConfigTemplate(id, body.name(), body.description(), body.recording(),
                body.stream(), body.image()));
    }

    @DeleteMapping("/api/v1/config-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        templates.delete(me(), id);
    }

    @PostMapping("/api/v1/config-templates/{id}/apply")
    public Application apply(@PathVariable UUID id, @RequestBody ApplyBody body) {
        return templates.apply(me(), id, body.cameraIds());
    }

    @GetMapping("/api/v1/template-applications/{id}")
    public Application application(@PathVariable UUID id) {
        return templates.application(me(), id);
    }

    @PostMapping("/api/v1/template-applications/{id}/reapply-failed")
    public Application reapply(@PathVariable UUID id) {
        return templates.reapplyFailed(me(), id);
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}
