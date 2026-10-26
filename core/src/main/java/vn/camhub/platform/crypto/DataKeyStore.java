package vn.camhub.platform.crypto;

import java.util.Optional;
import java.util.UUID;

/** Nơi lưu khóa dữ liệu đã mã hóa của từng Tổ chức. */
public interface DataKeyStore {

    Optional<String> find(UUID orgId);

    /** Ghi {@code wrappedKey} nếu Tổ chức chưa có khóa; trả về khóa đang được lưu sau thao tác. */
    String saveIfAbsent(UUID orgId, String wrappedKey);
}
