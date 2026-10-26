package vn.camhub.organization.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.platform.tenancy.OrganizationContext;

@Repository
public class OrganizationRepository {

    public record SiteRow(UUID id, String name, String address, String timezone) {
    }

    public record ZoneRow(UUID id, UUID siteId, String name) {
    }

    public record GroupRow(UUID id, String name, String description, int cameraCount) {
    }

    private final JdbcClient jdbc;

    public OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertOrganization(UUID id, String code, String name) {
        jdbc.sql("insert into organization (id, code, name) values (?, ?, ?)").params(id, code, name).update();
    }

    public long organizationCount() {
        return jdbc.sql("select camhub_organization_count()").query(Long.class).single();
    }

    public List<SiteRow> sites() {
        return jdbc.sql("select id, name, address, timezone from site order by name")
                .query((rs, i) -> new SiteRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4))).list();
    }

    public Optional<SiteRow> site(UUID id) {
        return jdbc.sql("select id, name, address, timezone from site where id = ?").param(id)
                .query((rs, i) -> new SiteRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4))).optional();
    }

    public UUID insertSite(String name, String address, String timezone) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into site (id, organization_id, name, address, timezone) values (?, ?, ?, ?, coalesce(?, 'Asia/Ho_Chi_Minh'))")
                .params(id, OrganizationContext.require(), name, address, timezone).update();
        return id;
    }

    public int updateSite(UUID id, String name, String address, String timezone) {
        return jdbc.sql("""
                update site set name = coalesce(?, name), address = coalesce(?, address), timezone = coalesce(?, timezone)
                where id = ?
                """).params(name, address, timezone, id).update();
    }

    public int deleteSite(UUID id) {
        return jdbc.sql("delete from site where id = ?").param(id).update();
    }

    public List<ZoneRow> zones() {
        return jdbc.sql("select id, site_id, name from zone order by name")
                .query((rs, i) -> new ZoneRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                .list();
    }

    public Optional<ZoneRow> zone(UUID id) {
        return jdbc.sql("select id, site_id, name from zone where id = ?").param(id)
                .query((rs, i) -> new ZoneRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                .optional();
    }

    public UUID insertZone(UUID siteId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into zone (id, organization_id, site_id, name) values (?, ?, ?, ?)")
                .params(id, OrganizationContext.require(), siteId, name).update();
        return id;
    }

    public int renameZone(UUID id, String name) {
        return jdbc.sql("update zone set name = ? where id = ?").params(name, id).update();
    }

    public int deleteZone(UUID id) {
        return jdbc.sql("delete from zone where id = ?").param(id).update();
    }

    public List<GroupRow> groups() {
        return jdbc.sql("""
                select g.id, g.name, g.description, (select count(*) from camera_group_member m where m.group_id = g.id)
                from camera_group g order by g.name
                """).query((rs, i) -> new GroupRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getInt(4))).list();
    }

    public UUID insertGroup(String name, String description) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into camera_group (id, organization_id, name, description) values (?, ?, ?, ?)")
                .params(id, OrganizationContext.require(), name, description).update();
        return id;
    }

    public int updateGroup(UUID id, String name, String description) {
        return jdbc.sql("update camera_group set name = coalesce(?, name), description = coalesce(?, description) where id = ?")
                .params(name, description, id).update();
    }

    public int deleteGroup(UUID id) {
        return jdbc.sql("delete from camera_group where id = ?").param(id).update();
    }

    public List<UUID> groupMembers(UUID groupId) {
        return jdbc.sql("select camera_id from camera_group_member where group_id = ?").param(groupId)
                .query(UUID.class).list();
    }

    public void addMember(UUID groupId, UUID cameraId) {
        jdbc.sql("insert into camera_group_member (organization_id, group_id, camera_id) values (?, ?, ?) on conflict do nothing")
                .params(OrganizationContext.require(), groupId, cameraId).update();
    }

    public void removeMember(UUID groupId, UUID cameraId) {
        jdbc.sql("delete from camera_group_member where group_id = ? and camera_id = ?").params(groupId, cameraId).update();
    }

    /** INV-19: đóng bản ghi vị trí hiện tại và mở bản ghi mới. */
    public void recordMove(UUID cameraId, UUID siteId, UUID zoneId, UUID movedBy) {
        jdbc.sql("update camera_location_history set valid_to = now() where camera_id = ? and valid_to is null")
                .param(cameraId).update();
        jdbc.sql("""
                insert into camera_location_history (organization_id, camera_id, site_id, zone_id, moved_by)
                values (?, ?, ?, ?, ?)
                """).params(OrganizationContext.require(), cameraId, siteId, zoneId, movedBy).update();
    }

    public record HistoryRow(UUID siteId, String siteName, UUID zoneId, String zoneName, java.time.Instant validFrom,
            java.time.Instant validTo, UUID movedBy) {
    }

    public List<HistoryRow> history(UUID cameraId) {
        return jdbc.sql("""
                select h.site_id, s.name, h.zone_id, z.name, h.valid_from, h.valid_to, h.moved_by
                from camera_location_history h join site s on s.id = h.site_id join zone z on z.id = h.zone_id
                where h.camera_id = ? order by h.valid_from desc
                """).param(cameraId).query((rs, i) -> new HistoryRow(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getObject(7, UUID.class)))
                .list();
    }
}
