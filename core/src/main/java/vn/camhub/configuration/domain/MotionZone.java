package vn.camhub.configuration.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Quy tắc vùng chuyển động và vùng che riêng tư (US-26): kiểm tra trước khi gửi xuống camera, theo giới hạn camera báo
 * (lưới ô, số đa giác tối đa, số vùng che và số điểm tối đa). Tọa độ chuẩn hóa 0..1.
 */
public final class MotionZone {

    public static final int MIN_POINTS = 3;
    public static final int MAX_POINTS = 64;

    public record Point(double x, double y) {
    }

    public record Violation(String code, String message) {
    }

    private MotionZone() {
    }

    /** Lưới ô phải đúng kích thước {@code rows × columns} của camera. */
    public static List<Violation> validateCells(List<List<Boolean>> cells, int columns, int rows) {
        if (cells == null || cells.size() != rows || cells.stream().anyMatch(r -> r == null || r.size() != columns)) {
            return List.of(new Violation("INVALID_ZONE", "Lưới ô phải có " + rows + " hàng × " + columns + " cột"));
        }
        return List.of();
    }

    public static List<Violation> validatePolygons(List<List<Point>> polygons, Integer maxPolygons) {
        List<Violation> out = new ArrayList<>();
        if (polygons == null || polygons.isEmpty()) {
            out.add(new Violation("INVALID_ZONE", "Cần ít nhất một vùng đa giác"));
            return out;
        }
        if (maxPolygons != null && polygons.size() > maxPolygons) {
            out.add(new Violation("LIMIT_EXCEEDED", "Camera hỗ trợ tối đa " + maxPolygons + " vùng chuyển động"));
        }
        polygons.forEach(p -> out.addAll(shape(p, MAX_POINTS)));
        return out;
    }

    public static List<Violation> validateMasks(List<List<Point>> masks, Integer maxMasks, Integer maxPoints) {
        List<Violation> out = new ArrayList<>();
        if (maxMasks != null && masks.size() > maxMasks) {
            out.add(new Violation("LIMIT_EXCEEDED", "Camera hỗ trợ tối đa " + maxMasks + " vùng che"));
        }
        int limit = maxPoints == null ? MAX_POINTS : maxPoints;
        masks.forEach(m -> out.addAll(shape(m, limit)));
        return out;
    }

    public static boolean validSensitivity(Integer s) {
        return s == null || (s >= 0 && s <= 100);
    }

    private static List<Violation> shape(List<Point> points, int maxPoints) {
        if (points == null || points.size() < MIN_POINTS) {
            return List.of(new Violation("INVALID_ZONE", "Mỗi vùng cần ít nhất " + MIN_POINTS + " điểm"));
        }
        if (points.size() > maxPoints) {
            return List.of(new Violation("LIMIT_EXCEEDED", "Mỗi vùng tối đa " + maxPoints + " điểm"));
        }
        if (points.stream().anyMatch(p -> p.x() < 0 || p.x() > 1 || p.y() < 0 || p.y() > 1)) {
            return List.of(new Violation("INVALID_ZONE", "Tọa độ điểm phải nằm trong khung hình"));
        }
        return List.of();
    }
}
