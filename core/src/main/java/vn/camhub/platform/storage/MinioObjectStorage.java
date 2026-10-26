package vn.camhub.platform.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@link ObjectStorage} trên MinIO bằng thư viện MinIO Java. */
@Component
@EnableConfigurationProperties(MinioObjectStorage.Properties.class)
public class MinioObjectStorage implements ObjectStorage {

    /**
     * @param endpoint  địa chỉ API S3 trong mạng backend, ví dụ http://minio:9000
     * @param accessKey tài khoản truy cập
     * @param secretKey mật khẩu, lấy từ Docker secret
     */
    @ConfigurationProperties("camhub.storage")
    public record Properties(String endpoint, String accessKey, String secretKey) {
    }

    private final MinioClient client;

    public MinioObjectStorage(Properties props) {
        this.client = MinioClient.builder()
                .endpoint(props.endpoint() == null ? "http://localhost:9000" : props.endpoint())
                .credentials(props.accessKey() == null ? "" : props.accessKey(),
                        props.secretKey() == null ? "" : props.secretKey().trim())
                .build();
    }

    @Override
    public InputStream open(String bucket, String key) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new StorageException("Không đọc được " + bucket + "/" + key, e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new StorageException("Không xóa được " + bucket + "/" + key, e);
        }
    }

    @Override
    public boolean healthy() {
        try {
            client.bucketExists(BucketExistsArgs.builder().bucket("camhub-exports").build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
