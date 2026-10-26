package vn.camhub.recording.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.recording.domain.RecordingPolicy;
import vn.camhub.recording.domain.RecordingPolicy.Mode;
import vn.camhub.recording.domain.RecordingPolicy.Slot;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Bảng recording_policy, recording_segment, recording_event, retention, evidence_hold (V4_1, V4_2). */
@Repository
public class RecordingRepository {

    public record Segment(UUID id, UUID cameraId, Instant startAt, Instant endAt, String location, long sizeBytes,
            boolean hasEvent) {
    }

    public record Hold(UUID id, UUID cameraId, Instant startAt, Instant endAt, String reason, UUID createdBy,
            Instant createdAt, Instant releasedAt) {
    }

    private static final TypeReference<List<Slot>> SLOTS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public RecordingRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<UUID> organizations() {
        return jdbc.sql("select camhub_organization_ids()").query(UUID.class).list();
    }

    // ---------------------------------------------------------------- chính sách ghi

    public Optional<RecordingPolicy> policy(UUID cameraId) {
        return jdbc.sql("""
                select camera_id, mode, schedule::text, pre_event_seconds, post_event_seconds
                from recording_policy where camera_id = ?
                """).param(cameraId).query((rs, i) -> new RecordingPolicy(rs.getObject(1, UUID.class),
                        Mode.valueOf(rs.getString(2)), slots(rs.getString(3)), rs.getInt(4), rs.getInt(5)))
                .optional();
    }

    public List<RecordingPolicy> policies() {
        return jdbc.sql("""
                select camera_id, mode, schedule::text, pre_event_seconds, post_event_seconds from recording_policy
                """).query((rs, i) -> new RecordingPolicy(rs.getObject(1, UUID.class), Mode.valueOf(rs.getString(2)),
                        slots(rs.getString(3)), rs.getInt(4), rs.getInt(5))).list();
    }

    public void savePolicy(RecordingPolicy p, UUID by) {
        jdbc.sql("""
                insert into recording_policy (camera_id, organization_id, mode, schedule, pre_event_seconds,
                                              post_event_seconds, updated_by, updated_at)
                values (?, ?, ?, cast(? as jsonb), ?, ?, ?, now())
                on conflict (camera_id) do update set mode = excluded.mode, schedule = excluded.schedule,
                    pre_event_seconds = excluded.pre_event_seconds, post_event_seconds = excluded.post_event_seconds,
                    updated_by = excluded.updated_by, updated_at = now()
                """).params(p.cameraId(), OrganizationContext.require(), p.mode().name(), toJson(p.schedule()),
                        p.preEventSeconds(), p.postEventSeconds(), by).update();
    }

    // ---------------------------------------------------------------- đoạn ghi

    public void insertSegment(UUID cameraId, Instant start, Instant end, String location, long size) {
        jdbc.sql("""
                insert into recording_segment (organization_id, camera_id, start_at, end_at, storage_location, size_bytes,
                                               has_event)
                values (?, ?, ?, ?, ?, ?, exists (select 1 from recording_event e where e.camera_id = ?
                                                   and e.occurred_at between ? and ?))
                """).params(OrganizationContext.require(), cameraId, ts(start), ts(end), location, size, cameraId,
                        ts(start), ts(end)).update();
    }

    public List<Segment> segments(UUID cameraId, Instant from, Instant to) {
        return jdbc.sql("""
                select id, camera_id, start_at, end_at, storage_location, size_bytes, has_event from recording_segment
                where camera_id = ? and start_at < ? and end_at > ? order by start_at
                """).params(cameraId, ts(to), ts(from)).query(RecordingRepository::segment).list();
    }

    public Optional<Segment> firstSegmentAfter(UUID cameraId, Instant at) {
        return jdbc.sql("""
                select id, camera_id, start_at, end_at, storage_location, size_bytes, has_event from recording_segment
                where camera_id = ? and end_at > ? order by start_at limit 1
                """).params(cameraId, ts(at)).query(RecordingRepository::segment).optional();
    }

    public Optional<Segment> lastSegmentBefore(UUID cameraId, Instant at) {
        return jdbc.sql("""
                select id, camera_id, start_at, end_at, storage_location, size_bytes, has_event from recording_segment
                where camera_id = ? and start_at < ? order by start_at desc limit 1
                """).params(cameraId, ts(at)).query(RecordingRepository::segment).optional();
    }

    /** Ngày (theo múi giờ {@code tz}) trong tháng có dữ liệu ghi – lịch trong màn hình Xem lại. */
    public List<String> recordingDays(UUID cameraId, Instant from, Instant to, String tz) {
        return jdbc.sql("""
                select distinct to_char(start_at at time zone ?, 'YYYY-MM-DD') d from recording_segment
                where camera_id = ? and start_at >= ? and start_at < ? order by d
                """).params(tz, cameraId, ts(from), ts(to)).query(String.class).list();
    }

    public List<Instant> events(UUID cameraId, Instant from, Instant to) {
        return jdbc.sql("""
                select occurred_at from recording_event where camera_id = ? and occurred_at between ? and ?
                order by occurred_at
                """).params(cameraId, ts(from), ts(to)).query((rs, i) -> rs.getTimestamp(1).toInstant()).list();
    }

    public void insertEvent(UUID cameraId, Instant at) {
        jdbc.sql("insert into recording_event (organization_id, camera_id, occurred_at) values (?, ?, ?)")
                .params(OrganizationContext.require(), cameraId, ts(at)).update();
        jdbc.sql("update recording_segment set has_event = true where camera_id = ? and start_at <= ? and end_at >= ?")
                .params(cameraId, ts(at), ts(at)).update();
    }

    /**
     * Đoạn của camera ghi theo sự kiện, đã kết thúc trước {@code before}, không giao với cửa sổ
     * [sự kiện − pre, sự kiện + post] nào và không nằm trong khoảng giữ lại.
     */
    public List<Segment> eventModeDiscardable(UUID cameraId, Instant before, int pre, int post, int limit) {
        return jdbc.sql("""
                select s.id, s.camera_id, s.start_at, s.end_at, s.storage_location, s.size_bytes, s.has_event
                from recording_segment s
                where s.camera_id = ? and s.end_at < ?
                  and not exists (select 1 from recording_event e where e.camera_id = s.camera_id
                                  and e.occurred_at - make_interval(secs => ?) < s.end_at
                                  and e.occurred_at + make_interval(secs => ?) > s.start_at)
                  and not exists (select 1 from evidence_hold h where h.camera_id = s.camera_id and h.released_at is null
                                  and h.start_at < s.end_at and h.end_at > s.start_at)
                order by s.start_at limit ?
                """).params(cameraId, ts(before), pre, post, limit).query(RecordingRepository::segment).list();
    }

    /** SADD 3.11.2: đoạn quá hạn (end_at < cutoff) không nằm trong khoảng giữ lại chưa giải phóng (INV-06, INV-07). */
    public List<Segment> expired(UUID cameraId, Instant cutoff, int limit) {
        return jdbc.sql("""
                select s.id, s.camera_id, s.start_at, s.end_at, s.storage_location, s.size_bytes, s.has_event
                from recording_segment s
                where s.camera_id = ? and s.end_at < ?
                  and not exists (select 1 from evidence_hold h where h.camera_id = s.camera_id and h.released_at is null
                                  and h.start_at < s.end_at and h.end_at > s.start_at)
                order by s.start_at limit ?
                """).params(cameraId, ts(cutoff), limit).query(RecordingRepository::segment).list();
    }

    public List<UUID> camerasWithSegments() {
        return jdbc.sql("select distinct camera_id from recording_segment").query(UUID.class).list();
    }

    public void deleteSegments(List<Segment> segs) {
        for (Segment s : segs) {
            jdbc.sql("delete from recording_segment where start_at = ? and id = ?").params(ts(s.startAt()), s.id())
                    .update();
        }
    }

    public int ensurePartitions() {
        return jdbc.sql("select camhub_segment_ensure_partitions(1, 3)").query(Integer.class).single();
    }

    // ---------------------------------------------------------------- lưu trữ, giữ lại

    public int defaultRetentionDays() {
        return jdbc.sql("select default_retention_days from retention_policy").query(Integer.class).optional()
                .orElse(30);
    }

    public void setDefaultRetentionDays(int days, UUID by) {
        jdbc.sql("""
                insert into retention_policy (organization_id, default_retention_days, updated_by) values (?, ?, ?)
                on conflict (organization_id) do update set default_retention_days = excluded.default_retention_days,
                    updated_by = excluded.updated_by, updated_at = now()
                """).params(OrganizationContext.require(), days, by).update();
    }

    public Optional<Integer> overrideDays(UUID cameraId) {
        return jdbc.sql("select retention_days from camera_retention_override where camera_id = ?").param(cameraId)
                .query(Integer.class).optional();
    }

    public void setOverride(UUID cameraId, Integer days, UUID by) {
        if (days == null) {
            jdbc.sql("delete from camera_retention_override where camera_id = ?").param(cameraId).update();
            return;
        }
        jdbc.sql("""
                insert into camera_retention_override (camera_id, organization_id, retention_days, updated_by)
                values (?, ?, ?, ?)
                on conflict (camera_id) do update set retention_days = excluded.retention_days,
                    updated_by = excluded.updated_by, updated_at = now()
                """).params(cameraId, OrganizationContext.require(), days, by).update();
    }

    public UUID insertHold(UUID cameraId, Instant start, Instant end, String reason, UUID by) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into evidence_hold (id, organization_id, camera_id, start_at, end_at, reason, created_by)
                values (?, ?, ?, ?, ?, ?, ?)
                """).params(id, OrganizationContext.require(), cameraId, ts(start), ts(end), reason, by).update();
        return id;
    }

    public Optional<Hold> hold(UUID id) {
        return jdbc.sql("select * from evidence_hold where id = ?").param(id).query(RecordingRepository::hold)
                .optional();
    }

    public List<Hold> holds(UUID cameraId) {
        return jdbc.sql("select * from evidence_hold where camera_id = ? order by start_at desc").param(cameraId)
                .query(RecordingRepository::hold).list();
    }

    public void releaseHold(UUID id, UUID by) {
        jdbc.sql("update evidence_hold set released_at = now(), released_by = ? where id = ? and released_at is null")
                .params(by, id).update();
    }

    private static Segment segment(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Segment(rs.getObject("id", UUID.class), rs.getObject("camera_id", UUID.class),
                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
                rs.getString("storage_location"), rs.getLong("size_bytes"), rs.getBoolean("has_event"));
    }

    private static Hold hold(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Timestamp rel = rs.getTimestamp("released_at");
        return new Hold(rs.getObject("id", UUID.class), rs.getObject("camera_id", UUID.class),
                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(), rs.getString("reason"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rel == null ? null : rel.toInstant());
    }

    private List<Slot> slots(String s) {
        try {
            return json.readValue(s, SLOTS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
