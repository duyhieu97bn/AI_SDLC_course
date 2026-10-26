package vn.camhub.notification.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.camhub.notification.application.NotificationService;
import vn.camhub.notification.infrastructure.ChannelRepository;
import vn.camhub.notification.infrastructure.NotificationRepository.Notification;
import vn.camhub.platform.security.CurrentUser;

/** Trung tâm thông báo của người dùng hiện tại (chuông trên thanh trên cùng). */
@RestController
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/v1/me/notifications")
    public List<Notification> list(@RequestParam(defaultValue = "false") boolean unread) {
        return notifications.list(me(), unread);
    }

    @GetMapping("/api/v1/me/notifications/unread-count")
    public Map<String, Long> unread() {
        return Map.of("count", notifications.unread(me()));
    }

    @PostMapping("/api/v1/me/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        notifications.read(me(), id);
    }

    @PostMapping("/api/v1/me/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() {
        notifications.readAll(me());
    }

    // ---------------------------------------------------------------- kênh nhận của tôi, kênh của Tổ chức (US-36.5)

    public record BindingBody(String address, Boolean enabled) {
    }

    public record ChannelConfigBody(boolean enabled, String secret) {
    }

    @GetMapping("/api/v1/me/channels")
    public List<ChannelRepository.Binding> myChannels() {
        return notifications.myChannels(me());
    }

    @PutMapping("/api/v1/me/channels/{channel}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bind(@PathVariable String channel, @RequestBody BindingBody body) {
        notifications.bind(me(), channel, body.address(), body.enabled() == null || body.enabled());
    }

    @DeleteMapping("/api/v1/me/channels/{channel}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable String channel) {
        notifications.unbind(me(), channel);
    }

    @GetMapping("/api/v1/notification-channels")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'MANAGE_SYSTEM')")
    public List<ChannelRepository.ChannelConfig> channelConfigs() {
        return notifications.configs();
    }

    @PutMapping("/api/v1/notification-channels/{channel}")
    @PreAuthorize("hasPermission(null, 'ORGANIZATION', 'MANAGE_SYSTEM')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void configure(@PathVariable String channel, @RequestBody ChannelConfigBody body) {
        notifications.configure(me(), channel, body.enabled(), body.secret());
    }

    private static UUID me() {
        return CurrentUser.require().userId();
    }
}
