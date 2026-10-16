package br.com.pixmanager.infrastructure.security.crypto;

import br.com.pixmanager.shared.properties.CryptoProperties;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    static final String AES = Base64.getEncoder().encodeToString(new byte[32]);
    static final String HMAC = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private CryptoService service() {
        return new CryptoService(new CryptoProperties(AES, HMAC, "v1"));
    }

    @Test
    void shouldRoundTripAndNeverStorePlaintext() {
        String encrypted = service().encrypt("52998224725");
        assertThat(encrypted).startsWith("v1:").doesNotContain("52998224725");
        assertThat(service().decrypt(encrypted)).isEqualTo("52998224725");
    }

    @Test
    void sameInputShouldProduceDifferentCiphertextButSameBlindIndex() {
        CryptoService s = service();
        assertThat(s.encrypt("52998224725")).isNotEqualTo(s.encrypt("52998224725"));
        assertThat(s.blindIndex("52998224725")).hasSize(64).isEqualTo(s.blindIndex("52998224725"));
        assertThat(s.blindIndex("52998224725")).isNotEqualTo(s.blindIndex("52998224726"));
    }

    @Test
    void shouldFailFastWithoutKeysInsteadOfFallingBackToPlaintext() {
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties("", HMAC, "v1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("aes-key");
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties(AES, null, "v1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hmac-key");
    }

    @Test
    void shouldRejectShortOrIdenticalKeys() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties(shortKey, HMAC, "v1")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties(AES, AES, "v1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("distintas");
    }

    @Test
    void shouldDetectTamperingAndUnknownKeyVersion() {
        String encrypted = service().encrypt("x");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + (encrypted.endsWith("AA") ? "BB" : "AA");
        assertThatThrownBy(() -> service().decrypt(tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service().decrypt("v9:" + encrypted.substring(3)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Versão");
    }

    // ---------------------------------------------------------------- rotação de chave (chaveiro de versões)

    static final String AES_V2 = Base64.getEncoder().encodeToString("abcdefghijklmnopqrstuvwxyz012345".getBytes());

    private CryptoService rotated() {
        return new CryptoService(new CryptoProperties(AES_V2, HMAC, "v2", java.util.Map.of("v1", AES), null));
    }

    @Test
    void afterRotationShouldStillDecryptOldDataButWriteWithTheNewVersion() {
        String old = service().encrypt("52998224725");               // gravado com v1
        CryptoService now = rotated();
        assertThat(now.decrypt(old)).isEqualTo("52998224725");        // chave antiga só para ler
        assertThat(now.isCurrentVersion(old)).isFalse();
        String fresh = now.encrypt("52998224725");
        assertThat(fresh).startsWith("v2:");
        assertThat(now.isCurrentVersion(fresh)).isTrue();
        assertThat(now.decrypt(fresh)).isEqualTo("52998224725");
        assertThat(now.currentVersion()).isEqualTo("v2");
    }

    @Test
    void dataFromAVersionWithoutAKeyShouldFailClearlyInsteadOfReturningGarbage() {
        String v1 = service().encrypt("x");
        CryptoService semAntiga = new CryptoService(new CryptoProperties(AES_V2, HMAC, "v2"));   // esqueceu previous-aes-keys
        assertThatThrownBy(() -> semAntiga.decrypt(v1)).hasMessageContaining("Versão de chave desconhecida");
        assertThatThrownBy(() -> semAntiga.decrypt("v9:AAAA")).hasMessageContaining("Versão de chave desconhecida");
        assertThatThrownBy(() -> semAntiga.decrypt("sem-prefixo")).hasMessageContaining("Versão de chave desconhecida");
    }

    @Test
    void shouldRejectInvalidPreviousKeys() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties(AES_V2, HMAC, "v2", java.util.Map.of("v2", AES), null)))
                .hasMessageContaining("repete a versão atual");
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties(AES_V2, HMAC, "v2", java.util.Map.of("v1", shortKey), null)))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new CryptoService(new CryptoProperties(AES_V2, HMAC, "v2", java.util.Map.of("v1", AES_V2), null)))
                .hasMessageContaining("igual");
    }

    @Test
    void blankPreviousKeysFromYamlPlaceholdersAreIgnored() {
        CryptoProperties props = new CryptoProperties(AES, HMAC, "v1", java.util.Map.of("v1x", "", "v2x", "  "), null);
        assertThat(props.previousAesKeys()).isEmpty();
        assertThat(new CryptoService(props).currentVersion()).isEqualTo("v1");
    }
}
