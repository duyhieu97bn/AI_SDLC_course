package vn.camhub.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxBackoffTest {

    @Test
    void backoffDoublesAndIsCappedAtFiveMinutes() {
        assertThat(OutboxPublisher.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxPublisher.backoff(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(OutboxPublisher.backoff(8)).isEqualTo(Duration.ofSeconds(256));
        assertThat(OutboxPublisher.backoff(9)).isEqualTo(Duration.ofMinutes(5));
        assertThat(OutboxPublisher.backoff(1_000)).isEqualTo(Duration.ofMinutes(5));
    }
}
