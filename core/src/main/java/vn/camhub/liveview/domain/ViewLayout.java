package vn.camhub.liveview.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Bố cục xem đã lưu (US-10). INV-13: gridSize ∈ {1,4,9,16}, số ô ≤ gridSize, vị trí không trùng. */
public record ViewLayout(UUID id, String name, int gridSize, Instant lastUsedAt, List<Tile> tiles) {

    public static final Set<Integer> GRID_SIZES = Set.of(1, 4, 9, 16);
    public static final int MAX_PER_USER = 10;

    public record Tile(int position, UUID cameraId, String streamMode) {
    }

    /** Trả về thông báo lỗi nếu vi phạm INV-13, hoặc null nếu hợp lệ. */
    public static String validate(int gridSize, List<Tile> tiles) {
        if (!GRID_SIZES.contains(gridSize)) {
            return "Lưới chỉ được là 1, 4, 9 hoặc 16 ô";
        }
        if (tiles.size() > gridSize) {
            return "Số camera vượt quá số ô của lưới";
        }
        Set<Integer> seen = new HashSet<>();
        for (Tile t : tiles) {
            if (t.position() < 0 || t.position() >= gridSize || !seen.add(t.position())) {
                return "Vị trí ô không hợp lệ";
            }
            if (!Set.of("AUTO", "HIGH", "LOW").contains(t.streamMode())) {
                return "Chế độ luồng không hợp lệ";
            }
        }
        return null;
    }
}
