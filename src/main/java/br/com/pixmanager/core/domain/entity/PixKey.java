package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.DeletionReason;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;

import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.InvalidTransitionException;

import java.time.Instant;
import java.util.UUID;

/**
 * Agregado da chave Pix. Imutável; toda mudança de estado passa por {@link #transition}, que valida
 * {@link PixKeyStatus#canTransitionTo}. {@code version} é a do {@code @Version} (If-Match/ETag).
 */
public record PixKey(UUID id, UUID accountId, PixKeyValue value, PixKeyStatus status, String dictCid,
                     Instant dictRegisteredAt, DeletionReason deletedReason, Instant deletedAt, long version,
                     Instant createdAt) {

    /** CPF/CNPJ/EVP vão direto ao DICT; PHONE/EMAIL esperam a prova de posse (BR-KEY-04). */
    public static PixKey register(UUID id, UUID accountId, PixKeyValue value, Instant now) {
        PixKeyStatus initial = value.type().requiresOwnershipProof()
                ? PixKeyStatus.PENDING_OWNERSHIP_PROOF : PixKeyStatus.PENDING_DICT;
        return new PixKey(id, accountId, value, initial, null, null, null, null, 0, now);
    }

    public PixKey ownershipProven() {
        return transition(PixKeyStatus.PENDING_DICT, dictCid, dictRegisteredAt, null, null);
    }

    public PixKey activated(String cid, Instant at) {
        return transition(PixKeyStatus.ACTIVE, cid, at, null, null);
    }

    public PixKey rejected() {
        return transition(PixKeyStatus.REJECTED, dictCid, dictRegisteredAt, null, null);
    }

    public PixKey deleted(DeletionReason reason, Instant at) {
        return transition(PixKeyStatus.DELETED, dictCid, dictRegisteredAt, reason, at);
    }

    public boolean isActive() {
        return status == PixKeyStatus.ACTIVE;
    }

    private PixKey transition(PixKeyStatus target, String cid, Instant registeredAt, DeletionReason reason, Instant deletedAt) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE,
                    "Transição de chave inválida: " + status + " -> " + target);
        }
        return new PixKey(id, accountId, value, target, cid, registeredAt, reason, deletedAt, version, createdAt);
    }
}
