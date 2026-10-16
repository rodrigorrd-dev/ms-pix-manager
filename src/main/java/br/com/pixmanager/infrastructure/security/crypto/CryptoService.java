package br.com.pixmanager.infrastructure.security.crypto;

import br.com.pixmanager.shared.exception.CryptoException;
import br.com.pixmanager.shared.properties.CryptoProperties;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Criptografia de PII em repouso (RF-DOC-04): AES-256-GCM (IV aleatório de 12 bytes prefixado) para o valor cifrado e
 * HMAC-SHA256 determinístico para o índice cego (busca por igualdade sem decifrar). Formato: {@code v1:base64(IV||ct||tag)}.
 *
 * <p><b>Fail-fast:</b> chave ausente/curta/igual à do HMAC ⇒ o contexto NÃO sobe. Não há fallback para texto puro — o
 * {@code DocumentoCryptoConverter} do mesa-credito-full gravava o CPF sem cifra quando o holder estava nulo.
 */
@Component
public class CryptoService {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA256";
    private static final int IV_SIZE = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec aesKey;
    /** Versão → chave AES. Contém a atual e as antigas (só para decifrar até a re-criptografia terminar). */
    private final java.util.Map<String, SecretKeySpec> aesByVersion = new java.util.HashMap<>();
    private final SecretKeySpec hmacKey;
    private final String version;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(CryptoProperties properties) {
        byte[] aes = decode(properties.aesKeyBase64(), "pix.crypto.aes-key-base64");
        byte[] hmac = decode(properties.hmacKeyBase64(), "pix.crypto.hmac-key-base64");
        if (aes.length != 32) {
            throw new IllegalStateException("pix.crypto.aes-key-base64 deve ter 32 bytes (AES-256)");
        }
        if (hmac.length < 32) {
            throw new IllegalStateException("pix.crypto.hmac-key-base64 deve ter ao menos 32 bytes");
        }
        if (MessageDigest.isEqual(aes, hmac)) {
            throw new IllegalStateException("As chaves AES e HMAC devem ser distintas");
        }
        this.aesKey = new SecretKeySpec(aes, "AES");
        this.hmacKey = new SecretKeySpec(hmac, HMAC);
        this.version = properties.keyVersion();
        this.aesByVersion.put(version, aesKey);
        properties.previousAesKeys().forEach((v, base64) -> {
            if (v.equals(version)) {
                throw new IllegalStateException("pix.crypto.previous-aes-keys." + v + " repete a versão atual (" + version + "): use outra versão");
            }
            byte[] old = decode(base64, "pix.crypto.previous-aes-keys." + v);
            if (old.length != 32) {
                throw new IllegalStateException("pix.crypto.previous-aes-keys." + v + " deve ter 32 bytes (AES-256)");
            }
            if (MessageDigest.isEqual(old, aes) || MessageDigest.isEqual(old, hmac)) {
                throw new IllegalStateException("A chave antiga " + v + " não pode ser igual à AES atual nem à HMAC");
            }
            aesByVersion.put(v, new SecretKeySpec(old, "AES"));
        });
    }

    public String encrypt(String text) {
        if (text == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_SIZE];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return version + ":" + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new CryptoException("Falha ao cifrar dado sensível", e);
        }
    }

    /** {@code true} se o dado já está cifrado com a versão ATUAL (nada a re-criptografar). */
    public boolean isCurrentVersion(String encrypted) {
        return encrypted != null && encrypted.startsWith(version + ":");
    }

    public String currentVersion() {
        return version;
    }

    public String decrypt(String encrypted) {
        if (encrypted == null) {
            return null;
        }
        try {
            int sep = encrypted.indexOf(':');
            SecretKeySpec key = sep < 0 ? null : aesByVersion.get(encrypted.substring(0, sep));
            if (key == null) {
                throw new CryptoException("Versão de chave desconhecida para o dado cifrado");
            }
            byte[] data = Base64.getDecoder().decode(encrypted.substring(sep + 1));
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_SIZE));
            return new String(cipher.doFinal(data, IV_SIZE, data.length - IV_SIZE), StandardCharsets.UTF_8);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("Falha ao decifrar dado sensível", e);
        }
    }

    /** Índice cego determinístico (HMAC-SHA256 em hex, 64 caracteres). */
    public String blindIndex(String text) {
        if (text == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CryptoException("Falha ao calcular índice cego", e);
        }
    }

    private static byte[] decode(String base64, String property) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("Propriedade obrigatória ausente: " + property);
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Propriedade não é Base64 válido: " + property);
        }
    }
}
