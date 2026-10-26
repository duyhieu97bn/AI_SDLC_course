package vn.camhub.platform.realtime;

import java.security.Principal;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import vn.camhub.platform.security.CurrentUser;
import vn.camhub.platform.security.JwtService;
import vn.camhub.platform.security.SessionValidator;

/**
 * WebSocket STOMP tại {@code /ws} (SADD 3.7.2). Xác thực ở frame CONNECT bằng header {@code Authorization: Bearer}
 * (trình duyệt không gửi được header khi mở WebSocket); chỉ cho đăng ký kênh của chính Tổ chức và của chính mình.
 *
 * <p>Broker trong bộ nhớ: đủ cho một bản Core. Khi chạy nhiều bản Core, chuyển sang STOMP relay của RabbitMQ.
 */
@Configuration
@EnableWebSocketMessageBroker
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService jwt;
    private final SessionValidator sessions;

    public WebSocketConfig(JwtService jwt, SessionValidator sessions) {
        this.jwt = jwt;
        this.sessions = sessions;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue").setHeartbeatValue(new long[] {20_000, 20_000})
                .setTaskScheduler(heartbeatScheduler());
        registry.setUserDestinationPrefix("/user");
        registry.setApplicationDestinationPrefixes("/app");
    }

    private static ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(1);
        s.setThreadNamePrefix("ws-heartbeat-");
        s.initialize();
        return s;
    }

    /** Principal của phiên WebSocket: tên = userId để gửi tới /user/{id}/queue/... */
    record StompPrincipal(CurrentUser user) implements Principal {
        @Override
        public String getName() {
            return user.userId().toString();
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (acc == null || acc.getCommand() == null) {
                    return message;
                }
                if (StompCommand.CONNECT.equals(acc.getCommand())) {
                    String header = acc.getFirstNativeHeader("Authorization");
                    if (header == null || !header.startsWith("Bearer ")) {
                        throw new BadCredentialsException("Thiếu access token");
                    }
                    CurrentUser user = jwt.parse(header.substring(7));
                    if (!sessions.isActive(user)) {
                        throw new BadCredentialsException("Phiên đã bị thu hồi");
                    }
                    acc.setUser(new StompPrincipal(user));
                } else if (StompCommand.SUBSCRIBE.equals(acc.getCommand())) {
                    if (!(acc.getUser() instanceof StompPrincipal p)) {
                        throw new AccessDeniedException("Chưa xác thực");
                    }
                    String dest = acc.getDestination() == null ? "" : acc.getDestination();
                    UUID org = p.user().organizationId();
                    boolean allowed = dest.startsWith("/topic/org/" + org + "/") || dest.startsWith("/user/queue/");
                    if (!allowed) {
                        throw new AccessDeniedException("Không được đăng ký kênh " + dest);
                    }
                }
                return message;
            }
        });
    }
}
