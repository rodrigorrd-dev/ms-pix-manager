package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.enums.LedgerStatus;

import br.com.pixmanager.shared.exception.InvalidTransitionException;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.core.domain.entity.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Reserva no core bancário. {@code idempotencyKey} = {@code endToEndId} da operação (pagamento/devolução) ou
 * {@code med-<caseId>-<n>} (bloqueio MED): repetir {@code reservar/confirmar/cancelar} com a mesma chave é seguro.
 * Imutável: cada transição devolve nova instância.
 */
public record LedgerReservation(UUID id, String idempotencyKey, UUID accountId, LedgerPurpose purpose,
                                String referenceType, UUID referenceId, Money amount, LedgerStatus status,
                                String ledgerRef, int attempts, Instant nextAttemptAt, String lastError, long version) {

    public static LedgerReservation pending(UUID id, String idempotencyKey, UUID accountId, LedgerPurpose purpose,
                                            String referenceType, UUID referenceId, Money amount, Instant now) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("valor da reserva deve ser positivo");
        }
        return new LedgerReservation(id, idempotencyKey, accountId, purpose, referenceType, referenceId, amount,
                LedgerStatus.PENDING, null, 0, now, null, 0);
    }

    public LedgerReservation reserved(String ledgerRef) {
        return moveTo(LedgerStatus.RESERVED, ledgerRef, null, null);
    }

    public LedgerReservation confirmed() {
        return moveTo(LedgerStatus.CONFIRMED, ledgerRef, null, null);
    }

    public LedgerReservation cancelled() {
        return moveTo(LedgerStatus.CANCELLED, ledgerRef, null, null);
    }

    public LedgerReservation failed(String reason) {
        return moveTo(LedgerStatus.FAILED, ledgerRef, null, reason);
    }

    public LedgerReservation withError(String error) {
        return new LedgerReservation(id, idempotencyKey, accountId, purpose, referenceType, referenceId, amount, status,
                ledgerRef, attempts, nextAttemptAt, error, version);
    }

    public LedgerReservation withLedgerRef(String newRef) {
        return new LedgerReservation(id, idempotencyKey, accountId, purpose, referenceType, referenceId, amount, status,
                newRef, attempts, nextAttemptAt, lastError, version);
    }

    /** Falha técnica: continua no mesmo estado, conta a tentativa e agenda a próxima. */
    public LedgerReservation technicalFailure(String error, Instant next) {
        return new LedgerReservation(id, idempotencyKey, accountId, purpose, referenceType, referenceId, amount, status,
                ledgerRef, attempts + 1, next, error, version);
    }

    private LedgerReservation moveTo(LedgerStatus target, String ref, Instant next, String error) {
        if (status == target) {
            return this;                         // idempotente: repetir a mesma transição é no-op
        }
        if (!status.canTransitionTo(target)) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE,
                    "Transição de reserva inválida: " + status + " -> " + target);
        }
        return new LedgerReservation(id, idempotencyKey, accountId, purpose, referenceType, referenceId, amount, target,
                ref, attempts, next, error, version);
    }
}
