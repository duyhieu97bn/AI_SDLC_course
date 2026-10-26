package vn.camhub.platform.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tổ chức (tenant) của luồng xử lý hiện tại.
 *
 * <p>{@link TenantAwareDataSource} đọc giá trị này mỗi khi lấy kết nối và đặt biến phiên
 * {@code app.org_id}; chính sách RLS trong PostgreSQL dựa vào biến đó (INV-01).
 * Khi chưa đặt Tổ chức, biến rỗng và mọi bảng có RLS trả về 0 dòng (đóng khi lỗi).
 */
public final class OrganizationContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private OrganizationContext() {
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw new MissingOrganizationException();
        }
        return id;
    }

    /** Chạy {@code work} trong ngữ cảnh Tổ chức {@code orgId}, khôi phục ngữ cảnh cũ sau đó. */
    public static <T> T callAs(UUID orgId, Supplier<T> work) {
        UUID previous = CURRENT.get();
        CURRENT.set(orgId);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    public static void runAs(UUID orgId, Runnable work) {
        callAs(orgId, () -> {
            work.run();
            return null;
        });
    }

    static void set(UUID orgId) {
        CURRENT.set(orgId);
    }

    static void clear() {
        CURRENT.remove();
    }

    private static void restore(UUID previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static final class MissingOrganizationException extends IllegalStateException {
        public MissingOrganizationException() {
            super("Chưa xác định Tổ chức cho yêu cầu hiện tại");
        }
    }
}
