package vn.camhub.access.api;

import java.util.Set;
import java.util.UUID;

/**
 * Tập camera người dùng được thấy với một quyền, dạng dùng trực tiếp trong câu SQL lọc danh sách:
 * {@code all} hoặc camera thuộc {@code siteIds}, thuộc một nhóm trong {@code groupIds}, hoặc nằm trong {@code cameraIds}.
 */
public record ScopeFilter(boolean all, Set<UUID> siteIds, Set<UUID> groupIds, Set<UUID> cameraIds) {

    public static final ScopeFilter NONE = new ScopeFilter(false, Set.of(), Set.of(), Set.of());
    public static final ScopeFilter ALL = new ScopeFilter(true, Set.of(), Set.of(), Set.of());

    public ScopeFilter {
        siteIds = Set.copyOf(siteIds);
        groupIds = Set.copyOf(groupIds);
        cameraIds = Set.copyOf(cameraIds);
    }

    public boolean isEmpty() {
        return !all && siteIds.isEmpty() && groupIds.isEmpty() && cameraIds.isEmpty();
    }

    public ScopeFilter union(ScopeFilter o) {
        if (all || o.all) {
            return ALL;
        }
        return new ScopeFilter(false, merge(siteIds, o.siteIds), merge(groupIds, o.groupIds),
                merge(cameraIds, o.cameraIds));
    }

    private static Set<UUID> merge(Set<UUID> a, Set<UUID> b) {
        var s = new java.util.HashSet<>(a);
        s.addAll(b);
        return s;
    }
}
