package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.OccurrenceStatus;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.InvalidTransitionException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Ocorrência de um agendamento. Imutável: cada transição devolve nova instância; transição inválida lança. */
public record ScheduledOccurrence(UUID id, UUID scheduledPaymentId, int sequenceNo, LocalDate dueDate, OccurrenceStatus status,
                                  int attemptCount, Instant nextAttemptAt, Instant customerNotifiedAt, UUID paymentOrderId,
                                  String lastErrorCode, long version) {

    public static ScheduledOccurrence scheduled(UUID id, UUID scheduledPaymentId, int sequenceNo, LocalDate dueDate) {
        return new ScheduledOccurrence(id, scheduledPaymentId, sequenceNo, dueDate, OccurrenceStatus.SCHEDULED, 0, null, null, null, null, 0);
    }

    /** Elegível ao worker: hoje ou atrasada, aguardando, e (se for retentativa) com o horário vencido. */
    public boolean isDue(LocalDate today, Instant now) {
        return (status == OccurrenceStatus.SCHEDULED || status == OccurrenceStatus.RETRY_PENDING)
                && !dueDate.isAfter(today) && (nextAttemptAt == null || !nextAttemptAt.isAfter(now));
    }

    public ScheduledOccurrence startProcessing(UUID orderId) {
        require(status == OccurrenceStatus.SCHEDULED || status == OccurrenceStatus.RETRY_PENDING, OccurrenceStatus.PROCESSING);
        return new ScheduledOccurrence(id, scheduledPaymentId, sequenceNo, dueDate, OccurrenceStatus.PROCESSING, attemptCount + 1, null,
                customerNotifiedAt, orderId, lastErrorCode, version);
    }

    public ScheduledOccurrence executed() {
        require(status == OccurrenceStatus.PROCESSING, OccurrenceStatus.EXECUTED);
        return with(OccurrenceStatus.EXECUTED, null, customerNotifiedAt, null);
    }

    /** Saldo insuficiente: reagenda na janela (e marca o aviso ao cliente) ou, sem janela/tentativas, falha definitiva. */
    public ScheduledOccurrence insufficientFunds(Instant retryAt, Instant now) {
        require(status == OccurrenceStatus.PROCESSING, OccurrenceStatus.RETRY_PENDING);
        if (retryAt == null) {
            return with(OccurrenceStatus.FAILED_INSUFFICIENT_FUNDS, null, customerNotifiedAt == null ? now : customerNotifiedAt, "AM04");
        }
        return with(OccurrenceStatus.RETRY_PENDING, retryAt, customerNotifiedAt == null ? now : customerNotifiedAt, "AM04");
    }

    /** Qualquer outra falha é definitiva para a ocorrência (BR-SCH-03); as seguintes seguem (BR-SCH-05). */
    public ScheduledOccurrence failed(String errorCode) {
        require(status == OccurrenceStatus.PROCESSING, OccurrenceStatus.FAILED);
        return with(OccurrenceStatus.FAILED, null, customerNotifiedAt, errorCode);
    }

    public ScheduledOccurrence cancelled() {
        if (!status.isCancellable()) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "Ocorrência em " + status + " não pode ser cancelada.");
        }
        return with(OccurrenceStatus.CANCELLED, null, customerNotifiedAt, lastErrorCode);
    }

    private ScheduledOccurrence with(OccurrenceStatus s, Instant next, Instant notified, String error) {
        return new ScheduledOccurrence(id, scheduledPaymentId, sequenceNo, dueDate, s, attemptCount, next, notified, paymentOrderId, error, version);
    }

    private void require(boolean ok, OccurrenceStatus target) {
        if (!ok) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "Transição inválida da ocorrência: " + status + " → " + target);
        }
    }
}
