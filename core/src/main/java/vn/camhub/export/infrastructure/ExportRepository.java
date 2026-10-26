package vn.camhub.export.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.export.domain.ExportJob;
import vn.camhub.export.domain.ExportJob.Status;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Bảng export_job (V5_1). */
@Repository
public class ExportRepository {

    private static final String COLUMNS = """
            id, camera_id, camera_name, start_at, end_at, status, requested_by, requested_at, completed_at, expires_at,
            bucket, object_key, size_bytes, error_code, error_message""";

    private final JdbcClient jdbc;

    public ExportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID cameraId, String cameraName, Instant start, Instant end, UUID by, String bucket,
            String key) {
        jdbc.sql("""
                insert into export_job (id, organization_id, camera_id, camera_name, start_at, end_at, requested_by,
                                        bucket, object_key)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, OrganizationContext.require(), cameraId, cameraName, ts(start), ts(end), by, bucket, key)
                .update();
    }

    public Optional<ExportJob> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from export_job where id = ?").param(id)
                .query(ExportRepository::map).optional();
    }

    /** Clip của người dùng trong 7 ngày gần nhất, mới nhất trước. */
    public List<ExportJob> byUser(UUID userId, Instant since) {
        return jdbc.sql("select " + COLUMNS + " from export_job where requested_by = ? and requested_at >= ?"
                + " order by requested_at desc limit 100").params(userId, ts(since))
                .query(ExportRepository::map).list();
    }

    public long activeCount(UUID userId) {
        return jdbc.sql("select count(*) from export_job where requested_by = ? and status in ('QUEUED', 'PROCESSING')")
                .param(userId).query(Long.class).single();
    }

    /** Đổi trạng thái có điều kiện trạng thái hiện tại (chống kết quả đến muộn/trùng). */
    public boolean transition(UUID id, Status from, Status to) {
        return jdbc.sql("update export_job set status = ?, started_at = case when ? = 'PROCESSING' then now()"
                + " else started_at end where id = ? and status = ?")
                .params(to.name(), to.name(), id, from.name()).update() == 1;
    }

    public boolean markReady(UUID id, Status from, Instant completedAt, Instant expiresAt, long size, String sha256) {
        return jdbc.sql("""
                update export_job set status = 'READY', completed_at = ?, expires_at = ?, size_bytes = ?, sha256 = ?
                where id = ? and status = ?
                """).params(ts(completedAt), ts(expiresAt), size, sha256, id, from.name()).update() == 1;
    }

    public boolean markFailed(UUID id, Status from, Instant at, String code, String message) {
        return jdbc.sql("""
                update export_job set status = 'FAILED', completed_at = ?, error_code = ?, error_message = left(?, 500)
                where id = ? and status = ?
                """).params(ts(at), code, message, id, from.name()).update() == 1;
    }

    public List<ExportJob> readyExpiredBefore(Instant now) {
        return jdbc.sql("select " + COLUMNS + " from export_job where status = 'READY' and expires_at <= ? limit 500")
                .param(ts(now)).query(ExportRepository::map).list();
    }

    public List<ExportJob> stuckBefore(Instant cutoff) {
        return jdbc.sql("select " + COLUMNS + " from export_job where status in ('QUEUED', 'PROCESSING')"
                + " and requested_at < ? limit 500").param(ts(cutoff)).query(ExportRepository::map).list();
    }

    private static ExportJob map(ResultSet rs, int i) throws SQLException {
        Long size = rs.getObject("size_bytes", Long.class);
        return new ExportJob(rs.getObject("id", UUID.class), rs.getObject("camera_id", UUID.class),
                rs.getString("camera_name"), rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(), Status.valueOf(rs.getString("status")),
                rs.getObject("requested_by", UUID.class), rs.getTimestamp("requested_at").toInstant(),
                instant(rs, "completed_at"), instant(rs, "expires_at"), rs.getString("bucket"),
                rs.getString("object_key"), size, rs.getString("error_code"),
                rs.getString("error_message"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
