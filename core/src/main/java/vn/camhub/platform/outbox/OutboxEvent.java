package vn.camhub.platform.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Một sự kiện chờ gửi sang RabbitMQ, lưu trong bảng {@code outbox_event} cùng giao dịch nghiệp vụ.
 *
 * @param id             cũng là {@code messageId} của thông điệp, để consumer bỏ qua bản lặp
 * @param organizationId Tổ chức sinh sự kiện; {@code null} với sự kiện cấp hệ thống
 * @param payload        JSON đã tuần tự hóa
 */
public record OutboxEvent(
        UUID id,
        UUID organizationId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String exchange,
        String routingKey,
        String payload,
        Instant createdAt,
        int attempts) {
}
