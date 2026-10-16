package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.AutomaticPeriodicity;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.InvalidTransitionException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Autorização (recorrência) de Pix Automático, lado pagador (BR-AUT-01..04). Imutável. {@code status}: CRIADA → APROVADA | REJEITADA |
 * EXPIRADA | CANCELADA (a aprovada pode ser cancelada); {@code requestStatus}: RECEBIDA → ACEITA | REJEITADA | EXPIRADA | CANCELADA.
 */
public record AutomaticAuthorization(UUID id, String idRec, boolean allowRetry, UUID payerAccountId, String receiverDocument, String receiverName,
                                     String contract, String objectDescription, AutomaticPeriodicity periodicity, LocalDate startDate,
                                     LocalDate endDate, Money fixedAmount, Money receiverMinAmount, Money payerMaxAmount, String status,
                                     String requestStatus, String rejectionCode, String cancelledBy, Instant approvedAt, Instant cancelledAt,
                                     Instant requestExpiresAt, long version) {

    private static final Pattern ID_REC = Pattern.compile("^R[RN][a-zA-Z0-9]{27}$");
    public static final Set<String> REJECTION_CODES = Set.of("AP13", "AP14", "AP15");
    public static final Set<String> CANCELLERS = Set.of("PAYER", "RECEIVER", "PSP");

    public static AutomaticAuthorization received(UUID id, String idRec, UUID payerAccountId, String receiverDocument, String receiverName,
                                                  String contract, String objectDescription, AutomaticPeriodicity periodicity, LocalDate start,
                                                  LocalDate end, Money fixedAmount, Money receiverMin, Instant requestExpiresAt) {
        if (idRec == null || !ID_REC.matcher(idRec).matches()) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "idRec inválido: 29 caracteres, R + (R|N) + 27 alfanuméricos.");
        }
        if (end != null && end.isBefore(start)) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "A data final não pode ser anterior à inicial.");
        }
        if (fixedAmount != null && !fixedAmount.isPositive() || receiverMin != null && !receiverMin.isPositive()) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "Os valores da autorização devem ser positivos.");
        }
        return new AutomaticAuthorization(id, idRec, idRec.charAt(1) == 'R', payerAccountId, receiverDocument, receiverName, contract, objectDescription,
                periodicity, start, end, fixedAmount, receiverMin, null, "CRIADA", "RECEBIDA", null, null, null, null, requestExpiresAt, 0);
    }

    /** O pagador aceita, opcionalmente limitando o valor (BR-AUT-02: o teto não pode ficar abaixo do mínimo do recebedor). */
    public AutomaticAuthorization approved(Money payerMax, Instant now) {
        requirePending();
        if (now.isAfter(requestExpiresAt)) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "A solicitação de autorização expirou.");
        }
        if (payerMax != null && (!payerMax.isPositive() || receiverMinAmount != null && payerMax.compareTo(receiverMinAmount) < 0
                || fixedAmount != null && payerMax.compareTo(fixedAmount) < 0)) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "O valor máximo do pagador não cobre o valor/mínimo da recorrência.");
        }
        return new AutomaticAuthorization(id, idRec, allowRetry, payerAccountId, receiverDocument, receiverName, contract, objectDescription, periodicity,
                startDate, endDate, fixedAmount, receiverMinAmount, payerMax, "APROVADA", "ACEITA", null, null, now, null, requestExpiresAt, version);
    }

    public AutomaticAuthorization rejected(String code) {
        requirePending();
        if (!REJECTION_CODES.contains(code)) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "Código de rejeição inválido (AP13, AP14 ou AP15).");
        }
        return with("REJEITADA", "REJEITADA", code, null, null, null);
    }

    public AutomaticAuthorization expired() {
        requirePending();
        return with("EXPIRADA", "EXPIRADA", null, null, null, null);
    }

    /** Cancela uma autorização pendente ou aprovada (BR-AUT-04). */
    public AutomaticAuthorization cancelled(String by, Instant now) {
        if (!CANCELLERS.contains(by)) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "Quem cancela deve ser PAYER, RECEIVER ou PSP.");
        }
        if (!"CRIADA".equals(status) && !"APROVADA".equals(status)) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "Autorização " + status + " não pode ser cancelada.");
        }
        return with("CANCELADA", "CANCELADA", null, by, approvedAt, now);
    }

    /** Ajuste do teto pelo pagador, a qualquer tempo, numa autorização aprovada (BR-AUT-04). */
    public AutomaticAuthorization withPayerMax(Money payerMax) {
        if (!"APROVADA".equals(status)) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "Só se ajusta o limite de uma autorização aprovada.");
        }
        if (payerMax == null || !payerMax.isPositive() || fixedAmount != null && payerMax.compareTo(fixedAmount) < 0
                || receiverMinAmount != null && payerMax.compareTo(receiverMinAmount) < 0) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "O valor máximo do pagador não cobre o valor/mínimo da recorrência.");
        }
        return new AutomaticAuthorization(id, idRec, allowRetry, payerAccountId, receiverDocument, receiverName, contract, objectDescription, periodicity,
                startDate, endDate, fixedAmount, receiverMinAmount, payerMax, status, requestStatus, rejectionCode, cancelledBy, approvedAt, cancelledAt,
                requestExpiresAt, version);
    }

    public boolean isApproved() {
        return "APROVADA".equals(status);
    }

    private void requirePending() {
        if (!"CRIADA".equals(status)) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "A autorização já foi decidida (" + status + ").");
        }
    }

    private AutomaticAuthorization with(String s, String rs, String rejection, String by, Instant approved, Instant cancelled) {
        return new AutomaticAuthorization(id, idRec, allowRetry, payerAccountId, receiverDocument, receiverName, contract, objectDescription, periodicity,
                startDate, endDate, fixedAmount, receiverMinAmount, payerMaxAmount, s, rs, rejection, by, approved, cancelled, requestExpiresAt, version);
    }
}
