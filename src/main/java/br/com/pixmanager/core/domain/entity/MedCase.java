package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.MedCaseType;
import br.com.pixmanager.core.domain.enums.MedRole;
import br.com.pixmanager.core.domain.enums.MedStatus;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.InvalidTransitionException;

import java.time.Instant;
import java.util.UUID;

/**
 * Caso de MED (BR-MED-01..08). Imutável: cada transição devolve uma instância e rejeita a inválida. {@code accountId} é a conta DO NOSSO
 * cliente (recebedora em REPORTED, pagadora em REPORTER) e define quem enxerga o caso. {@code layer}: 1 = transação original.
 */
public record MedCase(UUID id, String dictFundsRecoveryId, String endToEndId, UUID receivedId, UUID paymentOrderId, UUID accountId,
                      MedCaseType caseType, MedRole role, MedStatus status, int layer, String description, Instant reportedAt,
                      Instant analysisDeadlineAt, Money trackedAmount, Money blockedAmount, UUID refundId, long version) {

    public static MedCase open(UUID id, String fundsRecoveryId, String endToEndId, UUID receivedId, UUID orderId, UUID accountId, MedCaseType type,
                               MedRole role, int layer, String description, Instant now, Instant analysisDeadline, Money tracked) {
        return new MedCase(id, fundsRecoveryId, endToEndId, receivedId, orderId, accountId, type, role, MedStatus.OPEN, layer, description, now,
                analysisDeadline, tracked, Money.ZERO, null, 0);
    }

    public MedCase accepted() {
        return to(MedStatus.ACCEPTED, blockedAmount, refundId);
    }

    public MedCase refused() {
        return to(MedStatus.REFUSED, blockedAmount, refundId);
    }

    public MedCase cancelled() {
        return to(MedStatus.CANCELLED, blockedAmount, refundId);
    }

    public MedCase refunding(UUID refund) {
        return to(MedStatus.REFUNDING, blockedAmount, refund);
    }

    /** A devolução não se realizou: volta a ACCEPTED, sem bloqueio (foi liberado para devolver). */
    public MedCase refundFailed() {
        return to(MedStatus.ACCEPTED, Money.ZERO, null);
    }

    public MedCase closed() {
        return to(MedStatus.CLOSED, blockedAmount, refundId);
    }

    public MedCase contested() {
        return to(MedStatus.CONTESTED, blockedAmount, refundId);
    }

    public MedCase blocked(Money amount) {
        return new MedCase(id, dictFundsRecoveryId, endToEndId, receivedId, paymentOrderId, accountId, caseType, role, status, layer,
                description, reportedAt, analysisDeadlineAt, trackedAmount, amount, refundId, version);
    }

    public MedCase released() {
        return blocked(Money.ZERO);
    }

    private MedCase to(MedStatus target, Money blocked, UUID refund) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "Caso MED em " + status + " não pode ir para " + target + ".");
        }
        return new MedCase(id, dictFundsRecoveryId, endToEndId, receivedId, paymentOrderId, accountId, caseType, role, target, layer,
                description, reportedAt, analysisDeadlineAt, trackedAmount, blocked, refund, version);
    }
}
