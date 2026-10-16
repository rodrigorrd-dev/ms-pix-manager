package br.com.pixmanager.core.domain.entity.event;

import br.com.pixmanager.core.domain.entity.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code pix.schedule.v1}: {@code ScheduledPaymentCreated}, {@code ScheduledPaymentCancelled}, {@code ScheduledPaymentCompleted},
 * {@code ScheduledOccurrenceExecuted}, {@code ScheduledOccurrenceRetryNotified} (avisar o cliente do saldo insuficiente),
 * {@code ScheduledOccurrenceFailed}, {@code ScheduledOccurrenceCancelled}. {@code status} é o do agendamento OU o da ocorrência.
 */
public record ScheduledPaymentEvent(String eventType, UUID scheduledPaymentId, UUID occurrenceId, UUID payerAccountId, String status,
                                    LocalDate dueDate, Money amount, Instant nextAttemptAt, boolean customerNotificationRequired,
                                    UUID paymentOrderId) implements DomainEvent {
    @Override
    public String aggregateType() {
        return "schedule";
    }

    @Override
    public String aggregateId() {
        return scheduledPaymentId.toString();
    }
}
