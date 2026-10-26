package vn.camhub.liveview.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.camhub.access.api.AccessApi;
import vn.camhub.access.api.Permission;
import vn.camhub.liveview.domain.ViewLayout;
import vn.camhub.liveview.domain.ViewLayout.Tile;
import vn.camhub.liveview.infrastructure.LayoutRepository;
import vn.camhub.platform.security.ApiException;

/**
 * Bố cục xem của từng người (US-10): tối đa 10 bố cục; mở lại bố cục dùng gần nhất khi đăng nhập (AC1);
 * camera không còn quyền VIEW_LIVE bị loại khỏi bố cục đã lưu khi đọc (INV-13, AC2).
 */
@Service
public class LayoutService {

    private final LayoutRepository repo;
    private final AccessApi access;
    private final Clock clock;

    public LayoutService(LayoutRepository repo, AccessApi access, Clock clock) {
        this.repo = repo;
        this.access = access;
        this.clock = clock;
    }

    @Transactional
    public List<ViewLayout> list(UUID userId) {
        return repo.ofUser(userId).stream().map(l -> prune(userId, l)).toList();
    }

    @Transactional
    public ViewLayout create(UUID userId, String name, int gridSize, List<Tile> tiles) {
        if (repo.count(userId) >= ViewLayout.MAX_PER_USER) {
            throw new ApiException(409, "LAYOUT_LIMIT", "Mỗi người lưu tối đa 10 bố cục");
        }
        check(userId, gridSize, tiles);
        UUID id = repo.insert(userId, name.trim(), gridSize);
        repo.replaceTiles(id, tiles);
        return repo.find(userId, id).orElseThrow();
    }

    @Transactional
    public ViewLayout update(UUID userId, UUID id, String name, int gridSize, List<Tile> tiles) {
        repo.find(userId, id).orElseThrow(() -> ApiException.notFound("bố cục"));
        check(userId, gridSize, tiles);
        repo.update(id, name.trim(), gridSize);
        repo.replaceTiles(id, tiles);
        repo.touch(id, clock.instant());
        return repo.find(userId, id).orElseThrow();
    }

    @Transactional
    public void use(UUID userId, UUID id) {
        repo.find(userId, id).orElseThrow(() -> ApiException.notFound("bố cục"));
        repo.touch(id, clock.instant());
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        repo.find(userId, id).orElseThrow(() -> ApiException.notFound("bố cục"));
        repo.delete(id);
    }

    private void check(UUID userId, int gridSize, List<Tile> tiles) {
        String err = ViewLayout.validate(gridSize, tiles);
        if (err != null) {
            throw ApiException.badRequest("INVALID_LAYOUT", err);
        }
        for (Tile t : tiles) {
            if (!access.canOnCamera(userId, Permission.VIEW_LIVE, t.cameraId())) {
                throw ApiException.forbidden();
            }
        }
    }

    private ViewLayout prune(UUID userId, ViewLayout l) {
        List<Tile> keep = l.tiles().stream()
                .filter(t -> access.canOnCamera(userId, Permission.VIEW_LIVE, t.cameraId())).toList();
        if (keep.size() == l.tiles().size()) {
            return l;
        }
        l.tiles().stream().filter(t -> !keep.contains(t)).forEach(t -> repo.removeTile(l.id(), t.position()));
        return new ViewLayout(l.id(), l.name(), l.gridSize(), l.lastUsedAt(), keep);
    }
}
