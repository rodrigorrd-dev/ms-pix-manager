package br.com.pixmanager.shared.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Map;

/**
 * Chaves do documento em repouso, ambas Base64: {@code aesKeyBase64} = 32 bytes (AES-256-GCM); {@code hmacKeyBase64} =
 * ≥ 32 bytes (índice cego HMAC-SHA256), DISTINTAS entre si. Em hml/prd vêm de cofre (KMS/Vault); sem chave a
 * aplicação NÃO sobe (fail-fast). {@code keyVersion} prefixa o ciphertext ({@code v1:…}). {@code previousAesKeys} (versão → chave Base64) mantém as chaves ANTIGAS só para DECIFRAR dados ainda não re-criptografados; a escrita usa sempre a chave atual. O índice cego (HMAC) NÃO tem rotação: trocar a chave HMAC exige novo índice e leitura dupla (veja docs/runbooks).
 */
@ConfigurationProperties(prefix = "pix.crypto")
public record CryptoProperties(String aesKeyBase64, String hmacKeyBase64, String keyVersion,
                               Map<String, String> previousAesKeys, Reencrypt reencrypt) {

    @ConstructorBinding
    public CryptoProperties {
        if (keyVersion == null || keyVersion.isBlank()) {
            keyVersion = "v1";
        }
        // entradas em branco (placeholders do application.yml sem valor) não são chave: ignoradas
        previousAesKeys = previousAesKeys == null ? Map.of() : previousAesKeys.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        reencrypt = reencrypt == null ? new Reencrypt(false, 500) : reencrypt;
    }

    /** Sem chaves antigas nem re-criptografia (uso comum e testes). */
    public CryptoProperties(String aesKeyBase64, String hmacKeyBase64, String keyVersion) {
        this(aesKeyBase64, hmacKeyBase64, keyVersion, Map.of(), null);
    }

    /** Re-criptografia em lote (rotação de chave): ligue só em UMA instância, deixe terminar e desligue. */
    public record Reencrypt(boolean enabled, int batchSize) {
        public Reencrypt {
            batchSize = batchSize > 0 ? batchSize : 500;
        }
    }
}
