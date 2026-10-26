package vn.camhub.system.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Trạng thái sao lưu (US-40.3) do job sao lưu của đội vận hành ghi ra thư mục dùng chung
 * ({@code deploy/backup/}, volume {@code backupstatus}). Core chỉ đọc: khôi phục không làm từ giao diện (UCQ-03).
 *
 * <p>Chấp nhận một tệp {@code status.json} gồm {@code postgres} và {@code minio}, hoặc hai tệp riêng
 * {@code postgres.json}, {@code minio.json}.
 */
@Component
public class BackupStatusReader {

    private final Path dir;
    private final ObjectMapper json;

    public BackupStatusReader(@Value("${camhub.backup.status-dir:/backup-status}") String dir, ObjectMapper json) {
        this.dir = Path.of(dir);
        this.json = json;
    }

    /** Trạng thái hiện có; {@code available=false} khi chưa có tệp (chưa triển khai sao lưu). */
    public JsonNode read() {
        ObjectNode out = json.createObjectNode();
        try {
            Path combined = dir.resolve("status.json");
            if (Files.isRegularFile(combined)) {
                out.setAll((ObjectNode) json.readTree(combined.toFile()));
            } else {
                readPart(out, "postgres");
                readPart(out, "minio");
            }
        } catch (IOException | ClassCastException e) {
            out.put("error", "Không đọc được trạng thái sao lưu: " + e.getMessage());
        }
        out.put("available", out.has("postgres") || out.has("minio"));
        return out;
    }

    private void readPart(ObjectNode out, String name) throws IOException {
        Path p = dir.resolve(name + ".json");
        if (Files.isRegularFile(p)) {
            out.set(name, json.readTree(p.toFile()));
        }
    }
}
