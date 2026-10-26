package vn.camhub.notification.application;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.camhub.identity.api.IdentityApi;
import vn.camhub.notification.infrastructure.ChannelRepository;
import vn.camhub.notification.infrastructure.ChatSenders;
import vn.camhub.notification.infrastructure.NotificationRepository;
import vn.camhub.notification.infrastructure.NotificationRepository.Notification;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.storage.ObjectStorage;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Gửi thông báo qua kênh ngoài (US-34.2, US-36.4): email, Telegram (kèm ảnh sự kiện nếu có), Zalo. Mỗi 5 giây; lỗi
 * thì thử lại sau 1, 2 phút; quá 3 lần thì FAILED. Kênh chưa cấu hình (SMTP, token) thì FAILED ngay, không lặp vô hạn.
 */
@Component
public class NotificationDispatcher {

    static final int MAX_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    /** Kênh không dùng được (chưa cấu hình): thất bại hẳn, không thử lại. */
    static final class NotConfigured extends RuntimeException {
        NotConfigured(String message) {
            super(message);
        }
    }

    private final NotificationRepository repo;
    private final ChannelRepository channels;
    private final ChatSenders chat;
    private final ObjectStorage storage;
    private final CryptoService crypto;
    private final IdentityApi identity;
    private final ObjectProvider<JavaMailSender> mail;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String from;
    private final String baseUrl;
    private final boolean smtpConfigured;

    public NotificationDispatcher(NotificationRepository repo, ChannelRepository channels, ChatSenders chat,
            ObjectStorage storage, CryptoService crypto, IdentityApi identity, ObjectProvider<JavaMailSender> mail,
            TransactionTemplate tx, Clock clock,
            @Value("${camhub.notification.mail-from:CamHub <no-reply@camhub.local>}") String from,
            @Value("${camhub.notification.base-url:https://localhost}") String baseUrl,
            @Value("${spring.mail.host:}") String smtpHost) {
        this.repo = repo;
        this.channels = channels;
        this.chat = chat;
        this.storage = storage;
        this.crypto = crypto;
        this.identity = identity;
        this.mail = mail;
        this.tx = tx;
        this.clock = clock;
        this.from = from;
        this.baseUrl = baseUrl;
        this.smtpConfigured = !smtpHost.isBlank();
    }

    @Scheduled(fixedDelayString = "${camhub.notification.dispatch-interval:5000}", initialDelay = 20_000)
    public void dispatch() {
        for (UUID org : repo.organizationsWithPending()) {
            OrganizationContext.runAs(org, this::dispatchOrganization);
        }
    }

    public void dispatchOrganization() {
        tx.executeWithoutResult(s -> {
            for (Notification n : repo.lockPending(50)) {
                try {
                    switch (n.channel()) {
                        case "EMAIL" -> email(n);
                        case "TELEGRAM" -> telegram(n);
                        case "ZALO" -> zalo(n);
                        default -> throw new NotConfigured("Kênh không hỗ trợ: " + n.channel());
                    }
                    repo.markSent(n.id());
                } catch (RuntimeException e) {
                    int attempt = n.attempts() + 1;
                    int max = e instanceof NotConfigured ? attempt : MAX_ATTEMPTS;
                    log.warn("Gửi thông báo {} qua {} lần {} lỗi: {}", n.id(), n.channel(), attempt, e.getMessage());
                    repo.markAttemptFailed(n.id(), e.getMessage(), max,
                            clock.instant().plus(Duration.ofMinutes(1L << n.attempts())));
                }
            }
        });
    }

    private void email(Notification n) {
        JavaMailSender sender = smtpConfigured ? mail.getIfAvailable() : null;
        if (sender == null) {
            throw new NotConfigured("Chưa cấu hình máy chủ SMTP");
        }
        String to = identity.email(n.userId())
                .orElseThrow(() -> new NotConfigured("Người nhận không còn hoạt động"));
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("[CamHub] " + n.title());
        msg.setText(text(n));
        sender.send(msg);
    }

    private void telegram(Notification n) {
        String token = secret("TELEGRAM");
        String chatId = channels.address(n.userId(), "TELEGRAM")
                .orElseThrow(() -> new NotConfigured("Người nhận chưa liên kết Telegram"));
        chat.telegram(token, chatId, text(n), photo(n));
    }

    private void zalo(Notification n) {
        String token = secret("ZALO");
        String userId = channels.address(n.userId(), "ZALO")
                .orElseThrow(() -> new NotConfigured("Người nhận chưa liên kết Zalo"));
        chat.zalo(token, userId, text(n));
    }

    private String secret(String channel) {
        if (!channels.enabled(channel)) {
            throw new NotConfigured("Kênh " + channel + " chưa bật");
        }
        return channels.secretEnc(channel).map(crypto::decrypt)
                .orElseThrow(() -> new NotConfigured("Chưa cấu hình token kênh " + channel));
    }

    private byte[] photo(Notification n) {
        if (n.attachmentKey() == null) {
            return null;
        }
        try (InputStream in = storage.open(n.attachmentBucket(), n.attachmentKey())) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("Không đọc được ảnh sự kiện {}: {}", n.attachmentKey(), e.toString());
            return null; // vẫn gửi tin, không kèm ảnh
        }
    }

    private String text(Notification n) {
        return n.title() + (n.body() == null || n.body().isBlank() ? "" : "\n" + n.body())
                + (n.link() == null ? "" : "\n\nXem chi tiết: " + baseUrl + n.link());
    }
}
