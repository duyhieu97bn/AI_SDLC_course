package vn.camhub.platform.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param masterKeyFile đường dẫn Docker secret chứa khóa chính (32 byte, mã hóa base64)
 * @param masterKey     khóa chính dạng base64, chỉ dùng cho dev/test khi không có file
 * @param masterKeyId   mã khóa chính, ghi kèm khóa dữ liệu để quay vòng khóa hằng năm
 */
@ConfigurationProperties("camhub.crypto")
public record CryptoProperties(String masterKeyFile, String masterKey, String masterKeyId) {

    public CryptoProperties {
        if (masterKeyId == null || masterKeyId.isBlank()) {
            masterKeyId = "mk1";
        }
    }
}
