package vn.camhub.platform.outbox;

/** Kênh gửi sự kiện ra ngoài. Phải ném lỗi nếu broker chưa xác nhận đã nhận thông điệp. */
public interface OutboxTransport {

    void send(OutboxEvent event);
}
