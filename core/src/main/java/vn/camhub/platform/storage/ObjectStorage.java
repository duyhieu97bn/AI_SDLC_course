package vn.camhub.platform.storage;

import java.io.InputStream;

/** Kho đối tượng S3 (MinIO, SADD 3.3): clip đã xuất, ảnh sự kiện. Core chỉ đọc và xóa; Media Worker ghi. */
public interface ObjectStorage {

    /** Mở luồng đọc đối tượng; người gọi phải đóng luồng. */
    InputStream open(String bucket, String key);

    void delete(String bucket, String key);

    /** Kho phản hồi được (bảng Tổng quan, US-39). */
    boolean healthy();

    class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
