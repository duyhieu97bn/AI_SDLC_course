package vn.camhub.device.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.camhub.device.infrastructure.ConnectorRepository;
import vn.camhub.device.infrastructure.ConnectorRepository.Forward;
import vn.camhub.platform.crypto.CryptoService;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Ghi authfile của Tunnel Server (chisel {@code --authfile}, tự nạp lại khi tệp đổi): mỗi bộ kết nối đã kích hoạt một
 * tài khoản, chỉ được mở đúng cổng SOCKS và các cổng chuyển tiếp Core đã cấp. Ghi lại toàn bộ khi có thay đổi.
 */
@Component
public class TunnelAuthWriter {

    /** Có thay đổi tài khoản/cổng đường hầm (kích hoạt, thu hồi, cấp cổng). */
    public record TunnelAuthChanged() {
    }

    private static final Logger log = LoggerFactory.getLogger(TunnelAuthWriter.class);

    private final ConnectorRepository connectors;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final Path file;
    private final String remotePrefix;

    public TunnelAuthWriter(ConnectorRepository connectors, CryptoService crypto, ObjectMapper json,
            @Value("${camhub.tunnel.auth-file:/tunnel-auth/users.json}") String file,
            @Value("${camhub.tunnel.remote-prefix:R:0.0.0.0:}") String remotePrefix) {
        this.connectors = connectors;
        this.crypto = crypto;
        this.json = json;
        this.file = Path.of(file);
        this.remotePrefix = remotePrefix;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        write();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(TunnelAuthChanged e) {
        write();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onForwards(TunnelRouting.ForwardsChanged e) {
        write();
    }

    /** Ghi toàn bộ authfile; thư mục không tồn tại (máy dev/test không có Tunnel Server) thì bỏ qua. */
    public synchronized void write() {
        if (file.getParent() == null || !Files.isDirectory(file.getParent())) {
            return;
        }
        String escapedPrefix = remotePrefix.replace(".", "\\.");
        Map<String, List<String>> users = new LinkedHashMap<>();
        for (ConnectorRepository.Ref ref : connectors.activeAcrossOrganizations()) {
            try {
                OrganizationContext.runAs(ref.organizationId(), () -> connectors.find(ref.id()).ifPresent(c -> {
                    String pwd = connectors.tunnelPasswordEnc(c.id()).map(crypto::decrypt).orElse(null);
                    if (pwd == null || c.socksPort() == null) {
                        return;
                    }
                    List<String> remotes = new ArrayList<>();
                    remotes.add("^" + escapedPrefix + c.socksPort() + "$");
                    for (Forward f : connectors.forwards(c.id())) {
                        remotes.add("^" + escapedPrefix + f.port() + "$");
                    }
                    users.put(c.id() + ":" + pwd, remotes);
                }));
            } catch (RuntimeException ex) {
                log.warn("Bỏ qua bộ kết nối {} khi ghi authfile: {}", ref.id(), ex.toString());
            }
        }
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsString(users));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Đã ghi authfile đường hầm: {} bộ kết nối", users.size());
        } catch (IOException ex) {
            log.warn("Không ghi được authfile đường hầm {}: {}", file, ex.toString());
        }
    }
}
