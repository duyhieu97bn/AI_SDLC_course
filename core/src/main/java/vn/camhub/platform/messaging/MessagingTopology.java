package vn.camhub.platform.messaging;

import org.springframework.amqp.core.Declarables;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exchange dùng chung (SADD 3.7.3). RabbitMQ cũng nạp các exchange này từ
 * {@code deploy/rabbitmq/definitions.json}; khai báo lại ở đây để Core tự đủ khi chạy test.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingTopology {

    /** Lệnh Core gửi cho Media Worker, notification: ExportRequested, SnapshotRequested... */
    public static final String JOBS = "jobs";
    /** Kết quả Media Worker trả về: ExportCompleted, ExportFailed, SnapshotCreated. */
    public static final String JOB_RESULTS = "job.results";
    /** Sự kiện thiết bị từ Device Gateway: MotionDetected, DeviceOnline, DeviceOffline. */
    public static final String DEVICE_EVENTS = "device.events";
    /** Sự kiện nghiệp vụ của Core cho các module/consumer khác. */
    public static final String CORE_EVENTS = "core.events";

    /** Thông điệp JSON cho @RabbitListener; thông điệp không có content-type vẫn được coi là JSON. */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper json) {
        Jackson2JsonMessageConverter c = new Jackson2JsonMessageConverter(json);
        c.setAssumeSupportedContentType(true);
        return c;
    }

    @Bean
    Declarables camhubExchanges() {
        return new Declarables(
                ExchangeBuilder.topicExchange(JOBS).durable(true).build(),
                ExchangeBuilder.topicExchange(JOB_RESULTS).durable(true).build(),
                ExchangeBuilder.topicExchange(DEVICE_EVENTS).durable(true).build(),
                ExchangeBuilder.topicExchange(CORE_EVENTS).durable(true).build());
    }
}
