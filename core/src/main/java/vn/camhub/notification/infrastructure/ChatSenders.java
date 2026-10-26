package vn.camhub.notification.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Gửi tin qua Telegram Bot API và Zalo OA API (US-36.4). Địa chỉ API cấu hình được để dev/e2e dùng
 * {@code tools/chat-mock}. Lỗi từ nhà cung cấp ném {@link SendException} để dispatcher thử lại.
 */
@Component
public class ChatSenders {

    public static class SendException extends RuntimeException {
        public SendException(String message) {
            super(message);
        }
    }

    private final RestClient telegram;
    private final RestClient zalo;

    public ChatSenders(RestClient.Builder builder,
            @Value("${camhub.notification.telegram.base-url:https://api.telegram.org}") String telegramUrl,
            @Value("${camhub.notification.zalo.base-url:https://openapi.zalo.me}") String zaloUrl) {
        this.telegram = builder.clone().baseUrl(telegramUrl).build();
        this.zalo = builder.clone().baseUrl(zaloUrl).build();
    }

    /** Tin nhắn Telegram; có ảnh thì gửi sendPhoto kèm chú thích (giới hạn 1024 ký tự). */
    public void telegram(String botToken, String chatId, String text, byte[] photo) {
        try {
            JsonNode r;
            if (photo != null) {
                var form = new LinkedMultiValueMap<String, Object>();
                form.add("chat_id", chatId);
                form.add("caption", text.length() > 1024 ? text.substring(0, 1021) + "..." : text);
                form.add("photo", new ByteArrayResource(photo) {
                    @Override
                    public String getFilename() {
                        return "snapshot.jpg";
                    }
                });
                r = telegram.post().uri("/bot{token}/sendPhoto", botToken).contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(form).retrieve().body(JsonNode.class);
            } else {
                r = telegram.post().uri("/bot{token}/sendMessage", botToken).contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("chat_id", chatId, "text", text, "disable_web_page_preview", true)).retrieve()
                        .body(JsonNode.class);
            }
            if (r == null || !r.path("ok").asBoolean(false)) {
                throw new SendException("Telegram: " + (r == null ? "không phản hồi" : r.path("description").asText()));
            }
        } catch (RestClientResponseException e) {
            throw new SendException("Telegram " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        }
    }

    /** Tin nhắn chăm sóc khách hàng của Zalo OA (người nhận phải đã tương tác với OA). */
    public void zalo(String accessToken, String userId, String text) {
        try {
            JsonNode r = zalo.post().uri("/v3.0/oa/message/cs").header("access_token", accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("recipient", Map.of("user_id", userId), "message", Map.of("text", text))).retrieve()
                    .body(JsonNode.class);
            if (r == null || r.path("error").asInt(-1) != 0) {
                throw new SendException("Zalo: " + (r == null ? "không phản hồi" : r.path("message").asText()));
            }
        } catch (RestClientResponseException e) {
            throw new SendException("Zalo " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        }
    }
}
