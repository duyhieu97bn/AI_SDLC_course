package vn.camhub.liveview.infrastructure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.camhub.liveview.domain.ViewLayout;
import vn.camhub.liveview.domain.ViewLayout.Tile;
import vn.camhub.platform.tenancy.OrganizationContext;

@Repository
public class LayoutRepository {

    private final JdbcClient jdbc;

    public LayoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ViewLayout> ofUser(UUID userId) {
        Map<UUID, List<Tile>> tiles = new LinkedHashMap<>();
        jdbc.sql("""
                select t.layout_id, t.position, t.camera_id, t.stream_mode from layout_tile t
                join view_layout l on l.id = t.layout_id where l.user_id = ? order by t.position
                """).param(userId).query((rs, i) -> tiles.computeIfAbsent(rs.getObject(1, UUID.class),
                        k -> new ArrayList<>()).add(new Tile(rs.getInt(2), rs.getObject(3, UUID.class), rs.getString(4))))
                .list();
        return jdbc.sql("""
                select id, name, grid_size, last_used_at from view_layout where user_id = ?
                order by last_used_at desc nulls last, name
                """).param(userId).query((rs, i) -> {
                    UUID id = rs.getObject(1, UUID.class);
                    var used = rs.getTimestamp(4);
                    return new ViewLayout(id, rs.getString(2), rs.getInt(3), used == null ? null : used.toInstant(),
                            tiles.getOrDefault(id, List.of()));
                }).list();
    }

    public Optional<ViewLayout> find(UUID userId, UUID id) {
        return ofUser(userId).stream().filter(l -> l.id().equals(id)).findFirst();
    }

    public int count(UUID userId) {
        return jdbc.sql("select count(*) from view_layout where user_id = ?").param(userId).query(Integer.class)
                .single();
    }

    public UUID insert(UUID userId, String name, int gridSize) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into view_layout (id, organization_id, user_id, name, grid_size, last_used_at)
                values (?, ?, ?, ?, ?, now())
                """).params(id, OrganizationContext.require(), userId, name, gridSize).update();
        return id;
    }

    public void update(UUID id, String name, int gridSize) {
        jdbc.sql("update view_layout set name = ?, grid_size = ? where id = ?").params(name, gridSize, id).update();
    }

    public void replaceTiles(UUID layoutId, List<Tile> tiles) {
        jdbc.sql("delete from layout_tile where layout_id = ?").param(layoutId).update();
        for (Tile t : tiles) {
            jdbc.sql("""
                    insert into layout_tile (organization_id, layout_id, position, camera_id, stream_mode)
                    values (?, ?, ?, ?, ?)
                    """).params(OrganizationContext.require(), layoutId, t.position(), t.cameraId(), t.streamMode())
                    .update();
        }
    }

    public void removeTile(UUID layoutId, int position) {
        jdbc.sql("delete from layout_tile where layout_id = ? and position = ?").params(layoutId, position).update();
    }

    public void touch(UUID id, Instant at) {
        jdbc.sql("update view_layout set last_used_at = ? where id = ?").params(java.sql.Timestamp.from(at), id)
                .update();
    }

    public void delete(UUID id) {
        jdbc.sql("delete from view_layout where id = ?").param(id).update();
    }
}
