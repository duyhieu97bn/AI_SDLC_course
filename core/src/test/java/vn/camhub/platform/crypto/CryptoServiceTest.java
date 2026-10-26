package vn.camhub.platform.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    static String randomKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    static class MemoryStore implements DataKeyStore {
        final Map<UUID, String> keys = new ConcurrentHashMap<>();

        @Override
        public Optional<String> find(UUID orgId) {
            return Optional.ofNullable(keys.get(orgId));
        }

        @Override
        public String saveIfAbsent(UUID orgId, String wrappedKey) {
            return keys.computeIfAbsent(orgId, id -> wrappedKey);
        }
    }

    final String masterKey = randomKey();
    final MemoryStore store = new MemoryStore();
    final CryptoService crypto = new CryptoService(new CryptoProperties(null, masterKey, "mk1"), store);
    final UUID orgA = UUID.randomUUID();
    final UUID orgB = UUID.randomUUID();

    @Test
    void roundTrip() {
        String ct = crypto.encrypt(orgA, "mật-khẩu-camera");
        assertThat(ct).startsWith("v1:").doesNotContain("mật");
        assertThat(crypto.decrypt(orgA, ct)).isEqualTo("mật-khẩu-camera");
    }

    @Test
    void sameInputGivesDifferentCiphertext() {
        assertThat(crypto.encrypt(orgA, "x")).isNotEqualTo(crypto.encrypt(orgA, "x"));
    }

    @Test
    void eachOrganizationHasOwnDataKeyWrappedByMasterKey() {
        crypto.encrypt(orgA, "x");
        crypto.encrypt(orgB, "x");
        assertThat(store.keys).hasSize(2);
        assertThat(store.keys.get(orgA)).startsWith("mk1:").isNotEqualTo(store.keys.get(orgB));
    }

    @Test
    void ciphertextCannotBeMovedToAnotherOrganization() {
        String ct = crypto.encrypt(orgA, "secret");
        assertThatThrownBy(() -> crypto.decrypt(orgB, ct)).isInstanceOf(CryptoService.CryptoException.class);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        String ct = crypto.encrypt(orgA, "secret");
        byte[] raw = Base64.getDecoder().decode(ct.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> crypto.decrypt(orgA, tampered)).isInstanceOf(CryptoService.CryptoException.class);
    }

    @Test
    void dataKeySurvivesRestartWithSameMasterKey() {
        String ct = crypto.encrypt(orgA, "secret");
        CryptoService restarted = new CryptoService(new CryptoProperties(null, masterKey, "mk1"), store);
        assertThat(restarted.decrypt(orgA, ct)).isEqualTo("secret");
    }

    @Test
    void wrongMasterKeyCannotUnwrap() {
        String ct = crypto.encrypt(orgA, "secret");
        CryptoService other = new CryptoService(new CryptoProperties(null, randomKey(), "mk1"), store);
        assertThatThrownBy(() -> other.decrypt(orgA, ct)).isInstanceOf(CryptoService.CryptoException.class);
    }

    @Test
    void masterKeyMustBe256Bits() {
        String short16 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties(null, short16, "mk1"), store))
                .hasMessageContaining("32 byte");
    }
}
