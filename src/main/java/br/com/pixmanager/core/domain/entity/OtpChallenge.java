package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.OtpChannel;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Desafio de prova de posse (BR-KEY-04): o código NUNCA é guardado, só o HMAC dele ligado à chave. Imutável: cada mudança devolve uma cópia.
 */
public record OtpChallenge(UUID id, UUID pixKeyId, OtpChannel channel, String codeHash, int attempts, Instant expiresAt,
                           Instant verifiedAt, Instant createdAt) {

    public static OtpChallenge issue(UUID id, UUID pixKeyId, OtpChannel channel, String codeHash, Instant now, Duration ttl) {
        return new OtpChallenge(id, pixKeyId, channel, codeHash, 0, now.plus(ttl), null, now);
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public OtpChallenge failedAttempt() {
        return new OtpChallenge(id, pixKeyId, channel, codeHash, attempts + 1, expiresAt, verifiedAt, createdAt);
    }

    public OtpChallenge verified(Instant at) {
        return new OtpChallenge(id, pixKeyId, channel, codeHash, attempts, expiresAt, at, createdAt);
    }
}
