package vn.camhub.platform.realtime;

import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Đẩy sự kiện thời gian thực về trình duyệt (SADD 3.7.2): {@code /topic/org/{id}/<kênh>} cho cả Tổ chức,
 * {@code /user/queue/<kênh>} cho một người dùng.
 */
@Component
public class RealtimePublisher {

    private final SimpMessagingTemplate broker;

    public RealtimePublisher(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    public void toOrganization(UUID orgId, String channel, Object payload) {
        broker.convertAndSend("/topic/org/" + orgId + "/" + channel, payload);
    }

    public void toUser(UUID userId, String channel, Object payload) {
        broker.convertAndSendToUser(userId.toString(), "/queue/" + channel, payload);
    }
}
