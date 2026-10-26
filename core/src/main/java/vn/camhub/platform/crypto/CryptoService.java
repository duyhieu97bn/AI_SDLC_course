package vn.camhub.platform.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import vn.camhub.platform.tenancy.OrganizationContext;

/**
 * Mã hóa bí mật (mật khẩu camera/đầu ghi, LDAP, TOTP secret) bằng AES-256-GCM – NFR-05, INV-17.
 *
 * <p>Envelope encryption: mỗi Tổ chức có một khóa dữ liệu (DEK) ngẫu nhiên, lưu trong
 * {@code organization.data_key_enc} dưới dạng đã mã hóa bằng khóa chính (KEK) lấy từ Docker secret.
 * Bản mã luôn gắn mã Tổ chức làm AAD, nên không giải mã được nếu bị chép sang Tổ chức khác.
 *
 * <p>Định dạng bản mã: {@code v1:<base64(iv 12 byte || ciphertext || tag 16 byte)>}.
 */
@Service
public class CryptoService {

    static final String VERSION = "v1:";
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey masterKey;
    private final String masterKeyId;
    private final DataKeyStore keyStore;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, SecretKey> dataKeys = new ConcurrentHashMap<>();

    public CryptoService(CryptoProperties props, DataKeyStore keyStore) {
        this.masterKey = new SecretKeySpec(loadMasterKey(props), "AES");
        this.masterKeyId = props.masterKeyId();
        this.keyStore = keyStore;
    }

    /** Mã hóa {@code plaintext} bằng khóa của Tổ chức hiện tại. */
    public String encrypt(String plaintext) {
        return encrypt(OrganizationContext.require(), plaintext);
    }

    public String decrypt(String ciphertext) {
        return decrypt(OrganizationContext.require(), ciphertext);
    }

    public String encrypt(UUID orgId, String plaintext) {
        byte[] out = seal(dataKey(orgId), plaintext.getBytes(StandardCharsets.UTF_8), aad(orgId));
        return VERSION + Base64.getEncoder().encodeToString(out);
    }

    public String decrypt(UUID orgId, String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(VERSION)) {
            throw new CryptoException("Bản mã không đúng định dạng");
        }
        byte[] in = Base64.getDecoder().decode(ciphertext.substring(VERSION.length()));
        return new String(open(dataKey(orgId), in, aad(orgId)), StandardCharsets.UTF_8);
    }

    private SecretKey dataKey(UUID orgId) {
        return dataKeys.computeIfAbsent(orgId, this::loadOrCreateDataKey);
    }

    private SecretKey loadOrCreateDataKey(UUID orgId) {
        String wrapped = keyStore.find(orgId).orElseGet(() -> {
            byte[] fresh = new byte[KEY_BYTES];
            random.nextBytes(fresh);
            String candidate = wrap(orgId, fresh);
            Arrays.fill(fresh, (byte) 0);
            // Hai tiến trình có thể cùng tạo; DataKeyStore chỉ ghi khi cột còn trống và trả về giá trị thắng
            return keyStore.saveIfAbsent(orgId, candidate);
        });
        return new SecretKeySpec(unwrap(orgId, wrapped), "AES");
    }

    private String wrap(UUID orgId, byte[] dek) {
        byte[] out = seal(masterKey, dek, aad(orgId));
        return masterKeyId + ":" + Base64.getEncoder().encodeToString(out);
    }

    private byte[] unwrap(UUID orgId, String wrapped) {
        int sep = wrapped.indexOf(':');
        if (sep < 0 || !wrapped.substring(0, sep).equals(masterKeyId)) {
            throw new CryptoException("Khóa dữ liệu của Tổ chức " + orgId + " dùng khóa chính khác " + masterKeyId);
        }
        return open(masterKey, Base64.getDecoder().decode(wrapped.substring(sep + 1)), aad(orgId));
    }

    private byte[] seal(SecretKey key, byte[] plain, byte[] aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] ct = cipher.doFinal(plain);
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Mã hóa thất bại", e);
        }
    }

    private byte[] open(SecretKey key, byte[] in, byte[] aad) {
        if (in.length < IV_BYTES + TAG_BITS / 8) {
            throw new CryptoException("Bản mã quá ngắn");
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Giải mã thất bại: sai khóa hoặc dữ liệu đã bị sửa", e);
        }
    }

    private static byte[] aad(UUID orgId) {
        return orgId.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] loadMasterKey(CryptoProperties props) {
        String encoded;
        if (props.masterKeyFile() != null && !props.masterKeyFile().isBlank()) {
            try {
                encoded = Files.readString(Path.of(props.masterKeyFile())).trim();
            } catch (IOException e) {
                throw new CryptoException("Không đọc được khóa chính tại " + props.masterKeyFile(), e);
            }
        } else if (props.masterKey() != null && !props.masterKey().isBlank()) {
            encoded = props.masterKey().trim();
        } else {
            throw new CryptoException("Chưa cấu hình khóa chính (camhub.crypto.master-key-file)");
        }
        byte[] key = Base64.getDecoder().decode(encoded);
        if (key.length != KEY_BYTES) {
            throw new CryptoException("Khóa chính phải dài 32 byte (AES-256), hiện là " + key.length);
        }
        return key;
    }

    public static class CryptoException extends RuntimeException {
        public CryptoException(String message) {
            super(message);
        }

        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
