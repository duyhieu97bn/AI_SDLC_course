package vn.camhub.notification.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.camhub.identity.api.IdentityApi;
import vn.camhub.notification.api.NotificationApi;
import vn.camhub.notification.infrastructure.ChannelRepository;
import vn.camhub.notification.infrastructure.ChannelRepository.Binding;
import vn.camhub.notification.infrastructure.ChannelRepository.ChannelConfig;
import vn.camhub.notification.infrastructure.NotificationRepository;
import vn.camhub.notification.infrastructure.NotificationRepository.Notification;
import vn.camhub.platform.audit.AuditableEvent;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.realtime.RealtimePublisher;
import vn.camhub.platform.security.ApiException;

/**
 * Thông báo trên web (chuông, toast – US-42), email (US-34.2), Telegram và Zalo (US-36.4). Kênh ngoài chỉ gửi tới người
 * dùng đã liên kết kênh nhận (UserChannelBinding) và khi Tổ chức đã bật kênh đó.
 */
@Service
public class NotificationService implements NotificationApi {

    public record WebNotification(UUID id, String title, String body, String link, UUID alertId) {
    }

    private final NotificationRepository repo;
    private final ChannelRepository channels;
    private final IdentityApi identity;
    private final RealtimePublisher realtime;
    private final CryptoService crypto;
    private final ApplicationEventPublisher events;

    public NotificationService(NotificationRepository repo, ChannelRepository channels, IdentityApi identity,
            RealtimePublisher realtime, CryptoService crypto, ApplicationEventPublisher events) {
        this.repo = repo;
        this.channels = channels;
        this.identity = identity;
        this.realtime = realtime;
        this.crypto = crypto;
        this.events = events;
    }

    @Override
    public void send(Collection<UUID> userIds, Message m, Set<Channel> chosen) {
        send(userIds, m, chosen, 0);
    }

    @Override
    public void send(Collection<UUID> userIds, Message m, Set<Channel> chosen, int holdSeconds) {
        List<Runnable> pushes = new ArrayList<>();
        Instant notBefore = Instant.now().plusSeconds(holdSeconds);
        boolean telegram = chosen.contains(Channel.TELEGRAM) && channels.enabled("TELEGRAM");
        boolean zalo = chosen.contains(Channel.ZALO) && channels.enabled("ZALO");
        for (UUID user : new LinkedHashSet<>(userIds)) {
            if (chosen.contains(Channel.WEB)) {
                UUID id = repo.insert(user, m.alertId(), "WEB", m.title(), m.body(), m.link(), "SENT");
                pushes.add(() -> realtime.toUser(user, "notifications", new WebNotification(id, m.title(), m.body(),
                        m.link(), m.alertId())));
            }
            if (chosen.contains(Channel.EMAIL) && identity.email(user).isPresent()) {
                repo.insert(user, m.alertId(), "EMAIL", m.title(), m.body(), m.link(), "PENDING", notBefore);
            }
            if (telegram && channels.address(user, "TELEGRAM").isPresent()) {
                repo.insert(user, m.alertId(), "TELEGRAM", m.title(), m.body(), m.link(), "PENDING", notBefore);
            }
            if (zalo && channels.address(user, "ZALO").isPresent()) {
                repo.insert(user, m.alertId(), "ZALO", m.title(), m.body(), m.link(), "PENDING", notBefore);
            }
        }
        // Chỉ đẩy WebSocket khi dữ liệu đã commit, tránh chuông báo thứ không tồn tại
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pushes.forEach(Runnable::run);
                }
            });
        } else {
            pushes.forEach(Runnable::run);
        }
    }

    @Override
    public void attachSnapshot(UUID alertId, String bucket, String objectKey) {
        repo.attach(alertId, bucket, objectKey);
    }

    @Override
    public void releaseHeld(UUID alertId) {
        repo.release(alertId);
    }

    // ---------------------------------------------------------------- chuông

    public List<Notification> list(UUID user, boolean unreadOnly) {
        return repo.web(user, unreadOnly, 50);
    }

    public long unread(UUID user) {
        return repo.unread(user);
    }

    public void read(UUID user, UUID id) {
        repo.markRead(user, id);
    }

    public void readAll(UUID user) {
        repo.markAllRead(user);
    }

    // ---------------------------------------------------------------- kênh nhận của tôi (US-36.5)

    public List<Binding> myChannels(UUID user) {
        return channels.bindings(user);
    }

    public void bind(UUID user, String channel, String address, boolean enabled) {
        if (!Set.of("TELEGRAM", "ZALO").contains(channel)) {
            throw ApiException.badRequest("INVALID_CHANNEL", "Kênh không hợp lệ");
        }
        if (address == null || address.isBlank() || address.length() > 200) {
            throw ApiException.badRequest("INVALID_ADDRESS", "Nhập chat ID Telegram hoặc user ID Zalo");
        }
        channels.upsertBinding(user, channel, address.trim(), enabled);
    }

    public void unbind(UUID user, String channel) {
        channels.deleteBinding(user, channel);
    }

    // ---------------------------------------------------------------- kênh của Tổ chức (Hệ thống > Kênh thông báo)

    public List<ChannelConfig> configs() {
        return channels.configs();
    }

    /** {@code secret}: token bot Telegram / access token Zalo OA; rỗng = giữ token cũ. Lưu mã hóa (INV-17). */
    public void configure(UUID actor, String channel, boolean enabled, String secret) {
        if (!Set.of("TELEGRAM", "ZALO", "EMAIL").contains(channel)) {
            throw ApiException.badRequest("INVALID_CHANNEL", "Kênh không hợp lệ");
        }
        String enc = secret == null || secret.isBlank() ? null : crypto.encrypt(secret.trim());
        if (enabled && enc == null && !"EMAIL".equals(channel) && channels.secretEnc(channel).isEmpty()) {
            throw ApiException.badRequest("CHANNEL_SECRET_REQUIRED", "Nhập token của kênh trước khi bật");
        }
        channels.saveConfig(channel, enabled, enc, actor);
        events.publishEvent(AuditableEvent.of(actor, "CHANGE_CONFIG", "NOTIFICATION_CHANNEL", channel,
                Map.of("enabled", enabled, "tokenChanged", enc != null)));
    }
}
