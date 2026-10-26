package vn.camhub.configuration.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.configuration.domain.ConfigTemplate;
import vn.camhub.configuration.domain.ConfigTemplate.Detail;
import vn.camhub.configuration.domain.ConfigTemplate.ImageSpec;
import vn.camhub.configuration.domain.ConfigTemplate.RecordingSpec;
import vn.camhub.configuration.domain.ConfigTemplate.StreamSpec;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Bảng config_template, template_application, template_application_result (V8_1). */
@Repository
public class TemplateRepository {

    public record ResultRow(UUID cameraId, String cameraName, String result, List<Detail> details, Instant appliedAt) {
    }

    public record Application(UUID id, UUID templateId, String templateName, UUID startedBy, Instant startedAt,
            Instant finishedAt, List<ResultRow> results) {
    }

    private static final TypeReference<List<Detail>> DETAILS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public TemplateRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<ConfigTemplate> all() {
        return jdbc.sql("select id, name, description, recording::text, stream::text, image::text from config_template"
                + " order by name").query(this::map).list();
    }

    public Optional<ConfigTemplate> find(UUID id) {
        return jdbc.sql("select id, name, description, recording::text, stream::text, image::text from config_template"
                + " where id = ?").param(id).query(this::map).optional();
    }

    public void save(ConfigTemplate t, UUID by) {
        jdbc.sql("""
                insert into config_template (id, organization_id, name, description, recording, stream, image, created_by)
                values (?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?)
                on conflict (id) do update set name = excluded.name, description = excluded.description,
                    recording = excluded.recording, stream = excluded.stream, image = excluded.image, updated_at = now()
                """).params(t.id(), OrganizationContext.require(), t.name(), t.description(), write(t.recording()),
                write(t.stream()), write(t.image()), by).update();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("delete from config_template where id = ?").param(id).update() == 1;
    }

    public UUID startApplication(UUID templateId, UUID by) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into template_application (id, organization_id, template_id, started_by) values (?, ?, ?, ?)")
                .params(id, OrganizationContext.require(), templateId, by).update();
        return id;
    }

    public void saveResult(UUID applicationId, UUID cameraId, String cameraName, String result, List<Detail> details) {
        jdbc.sql("""
                insert into template_application_result (application_id, organization_id, camera_id, camera_name, result,
                                                         details)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """).params(applicationId, OrganizationContext.require(), cameraId, cameraName, result, write(details))
                .update();
    }

    public void finish(UUID applicationId) {
        jdbc.sql("update template_application set finished_at = now() where id = ?").param(applicationId).update();
    }

    public Optional<Application> application(UUID id) {
        return jdbc.sql("""
                select a.id, a.template_id, t.name, a.started_by, a.started_at, a.finished_at
                from template_application a join config_template t on t.id = a.template_id where a.id = ?
                """).param(id).query((rs, i) -> new Application(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), results(id)))
                .optional();
    }

    public List<ResultRow> results(UUID applicationId) {
        return jdbc.sql("select camera_id, camera_name, result, details::text, applied_at from template_application_result"
                + " where application_id = ? order by camera_name").param(applicationId)
                .query((rs, i) -> new ResultRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        read(rs.getString(4), DETAILS), rs.getTimestamp(5).toInstant()))
                .list();
    }

    private ConfigTemplate map(ResultSet rs, int i) throws SQLException {
        return new ConfigTemplate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                read(rs.getString(4), RecordingSpec.class), read(rs.getString(5), StreamSpec.class),
                read(rs.getString(6), ImageSpec.class));
    }

    private <T> T read(String s, Class<T> type) {
        try {
            return s == null ? null : json.readValue(s, type);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T read(String s, TypeReference<T> type) {
        try {
            return s == null ? null : json.readValue(s, type);
        } catch (Exception e) {
            return null;
        }
    }

    private String write(Object o) {
        try {
            return o == null ? null : json.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
