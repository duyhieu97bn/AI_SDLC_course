package vn.camhub.notification.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** API công khai của module notification (gọi trong giao dịch và ngữ cảnh Tổ chức của nghiệp vụ gửi). */
public interface NotificationApi {

    enum Channel { WEB, EMAIL, TELEGRAM, ZALO }

    /**
     * @param link   đường dẫn trong giao diện, ví dụ {@code /alerts/<id>}
     * @param alertId cảnh báo gốc nếu có
     */
    record Message(String title, String body, String link, UUID alertId) {
    }

    /**
     * Ghi thông báo cho từng người nhận theo từng kênh. WEB đẩy ngay qua WebSocket sau khi giao dịch commit; EMAIL
     * gửi bởi job nền, thử lại tối đa 3 lần (US-34.2).
     */
    void send(Collection<UUID> userIds, Message message, Set<Channel> channels);

    /**
     * Như {@link #send}, nhưng kênh ngoài (email, Telegram, Zalo) chờ tối đa {@code holdSeconds} để kèm ảnh sự kiện;
     * gọi {@link #attachSnapshot} hoặc {@link #releaseHeld} khi ảnh có kết quả (US-36).
     */
    void send(Collection<UUID> userIds, Message message, Set<Channel> channels, int holdSeconds);

    void attachSnapshot(UUID alertId, String bucket, String objectKey);

    void releaseHeld(UUID alertId);
}
