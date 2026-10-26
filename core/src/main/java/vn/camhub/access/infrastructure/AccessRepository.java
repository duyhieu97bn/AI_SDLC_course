package vn.camhub.access.infrastructure;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.access.api.Permission;
import vn.camhub.access.api.ScopeType;
import vn.camhub.access.domain.Grant;
import vn.camhub.platform.tenancy.OrganizationContext;

/** Truy cập bảng role, role_permission, role_assignment. Mọi truy vấn chạy dưới RLS của Tổ chức hiện tại. */
@Repository
public class AccessRepository {

    public record RoleRow(UUID id, String code, String name, String description, boolean system,
            boolean requireTwoFactor, int rank, Set<Permission> permissions, int assignedUsers) {
    }

    public record AssignmentRow(UUID id, UUID userId, UUID roleId, String roleName, ScopeType scopeType, UUID scopeId,
            String source) {
    }

    private final JdbcClient jdbc;

    /** Người dùng đang hoạt động có ít nhất một gán vai trò trong Tổ chức hiện tại. */
    public List<UUID> assignedUserIds() {
        return jdbc.sql("""
                select distinct a.user_id from role_assignment a join app_user u on u.id = a.user_id
                where u.status = 'ACTIVE'
                """).query(UUID.class).list();
    }

    public AccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Grant> grantsOf(UUID userId) {
        Map<UUID, List<Object[]>> byAssignment = new LinkedHashMap<>();
        jdbc.sql("""
                select a.id, a.role_id, a.scope_type, a.scope_id, p.permission
                from role_assignment a join role_permission p on p.role_id = a.role_id
                where a.user_id = ?
                """).param(userId).query((rs, i) -> {
                    byAssignment.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new Object[] {rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
                                rs.getString(5)});
                    return null;
                }).list();
        List<Grant> grants = new ArrayList<>();
        for (List<Object[]> rows : byAssignment.values()) {
            Set<Permission> perms = EnumSet.noneOf(Permission.class);
            rows.forEach(r -> perms.add(Permission.valueOf((String) r[3])));
            Object[] first = rows.get(0);
            grants.add(new Grant((UUID) first[0], perms, ScopeType.valueOf((String) first[1]), (UUID) first[2]));
        }
        return grants;
    }

    public List<RoleRow> roles() {
        Map<UUID, Set<Permission>> perms = new LinkedHashMap<>();
        jdbc.sql("select role_id, permission from role_permission").query((rs, i) -> {
            perms.computeIfAbsent(rs.getObject(1, UUID.class), k -> EnumSet.noneOf(Permission.class))
                    .add(Permission.valueOf(rs.getString(2)));
            return null;
        }).list();
        return jdbc.sql("""
                select r.id, r.code, r.name, r.description, r.is_system, r.require_two_factor, r.rank,
                       (select count(distinct a.user_id) from role_assignment a where a.role_id = r.id) as users
                from role r order by r.rank desc, r.name
                """).query((rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new RoleRow(id, rs.getString("code"), rs.getString("name"), rs.getString("description"),
                            rs.getBoolean("is_system"), rs.getBoolean("require_two_factor"), rs.getInt("rank"),
                            perms.getOrDefault(id, Set.of()), rs.getInt("users"));
                }).list();
    }

    public Optional<RoleRow> role(UUID roleId) {
        return roles().stream().filter(r -> r.id().equals(roleId)).findFirst();
    }

    public List<AssignmentRow> assignmentsOf(UUID userId) {
        return jdbc.sql("""
                select a.id, a.user_id, a.role_id, r.name, a.scope_type, a.scope_id, a.source
                from role_assignment a join role r on r.id = a.role_id
                where a.user_id = ? order by r.rank desc
                """).param(userId).query((rs, i) -> new AssignmentRow(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                        ScopeType.valueOf(rs.getString(5)), rs.getObject(6, UUID.class), rs.getString(7)))
                .list();
    }

    /** Người dùng có ít nhất một gán vai trò thuộc tập phạm vi đã cho. */
    public Set<UUID> usersAssignedWithin(Set<UUID> siteIds, Set<UUID> groupIds, Set<UUID> cameraIds) {
        return Set.copyOf(jdbc.sql("""
                select distinct user_id from role_assignment
                where (scope_type = 'SITE' and scope_id = any(?))
                   or (scope_type = 'GROUP' and scope_id = any(?))
                   or (scope_type = 'CAMERA' and scope_id = any(?))
                """).params(siteIds.toArray(UUID[]::new), groupIds.toArray(UUID[]::new), cameraIds.toArray(UUID[]::new))
                .query(UUID.class).list());
    }

    public List<UUID> usersWithRole(UUID roleId) {
        return jdbc.sql("select distinct user_id from role_assignment where role_id = ?").param(roleId)
                .query(UUID.class).list();
    }

    public void deleteDirectoryAssignments(UUID userId) {
        jdbc.sql("delete from role_assignment where user_id = ? and source = 'DIRECTORY'").param(userId).update();
    }

    public void deleteManualAssignments(UUID userId) {
        jdbc.sql("delete from role_assignment where user_id = ? and source = 'MANUAL'").param(userId).update();
    }

    public void insertAssignment(UUID userId, UUID roleId, ScopeType scopeType, UUID scopeId, String source,
            UUID createdBy) {
        jdbc.sql("""
                insert into role_assignment (organization_id, user_id, role_id, scope_type, scope_id, source, created_by)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """).params(OrganizationContext.require(), userId, roleId, scopeType.name(), scopeId, source, createdBy)
                .update();
    }

    public UUID insertRole(String code, String name, String description, boolean system, int rank,
            Set<Permission> permissions) {
        UUID orgId = OrganizationContext.require();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into role (id, organization_id, code, name, description, is_system, rank)
                values (?, ?, ?, ?, ?, ?, ?)
                """).params(id, orgId, code, name, description, system, rank).update();
        setPermissions(id, permissions);
        return id;
    }

    public void updateRole(UUID roleId, String name, String description) {
        jdbc.sql("update role set name = ?, description = ? where id = ? and not is_system")
                .params(name, description, roleId).update();
    }

    public void deleteRole(UUID roleId) {
        jdbc.sql("delete from role where id = ? and not is_system").param(roleId).update();
    }

    public void setRequireTwoFactor(UUID roleId, boolean required) {
        jdbc.sql("update role set require_two_factor = ? where id = ?").params(required, roleId).update();
    }

    public void setPermissions(UUID roleId, Set<Permission> permissions) {
        UUID orgId = OrganizationContext.require();
        jdbc.sql("delete from role_permission where role_id = ?").param(roleId).update();
        for (Permission p : permissions) {
            jdbc.sql("insert into role_permission (organization_id, role_id, permission) values (?, ?, ?)")
                    .params(orgId, roleId, p.name()).update();
        }
    }
}
