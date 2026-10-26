package vn.camhub.recording.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bảng storage_volume, storage_measurement (V5_2): ổ ghi hình dùng chung, không thuộc riêng Tổ chức. */
@Repository
public class StorageRepository {

    public record Volume(String name, String path, long totalBytes, long usedBytes, Instant measuredAt) {
    }

    public record Measurement(Instant at, long usedBytes) {
    }

    private final JdbcClient jdbc;

    public StorageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String name, String path, long total, long used, Instant at) {
        jdbc.sql("""
                insert into storage_volume (name, path, total_bytes, used_bytes, measured_at) values (?, ?, ?, ?, ?)
                on conflict (name) do update set path = excluded.path, total_bytes = excluded.total_bytes,
                    used_bytes = excluded.used_bytes, measured_at = excluded.measured_at
                """).params(name, path, total, used, Timestamp.from(at)).update();
        jdbc.sql("insert into storage_measurement (volume_name, measured_at, used_bytes) values (?, ?, ?)"
                + " on conflict do nothing").params(name, Timestamp.from(at), used).update();
        jdbc.sql("delete from storage_measurement where volume_name = ? and measured_at < ?")
                .params(name, Timestamp.from(at.minusSeconds(8 * 86_400))).update();
    }

    public List<Volume> volumes() {
        return jdbc.sql("select name, path, total_bytes, used_bytes, measured_at from storage_volume order by name")
                .query((rs, i) -> new Volume(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getTimestamp(5).toInstant()))
                .list();
    }

    /** Lần đo gần nhất trước hoặc tại {@code at}; không có thì lần đo sớm nhất (hệ thống mới chạy). */
    public Optional<Measurement> baseline(String name, Instant at) {
        return jdbc.sql("""
                (select measured_at, used_bytes from storage_measurement where volume_name = ? and measured_at <= ?
                 order by measured_at desc limit 1)
                union all
                (select measured_at, used_bytes from storage_measurement where volume_name = ?
                 order by measured_at limit 1)
                limit 1
                """).params(name, Timestamp.from(at), name)
                .query((rs, i) -> new Measurement(rs.getTimestamp(1).toInstant(), rs.getLong(2)))
                .optional();
    }
}
