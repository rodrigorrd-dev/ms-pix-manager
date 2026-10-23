package br.com.pixmanager.infrastructure.event.outbox.converter;

import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.core.domain.entity.event.ScheduledPaymentEvent;
import br.com.pixmanager.events.v1.EventEnvelope;
import br.com.pixmanager.infrastructure.event.outbox.EventAvroConverter;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

/** {@code pix.schedule.v1}. */
@Component
public class ScheduledPaymentEventAvroConverter implements EventAvroConverter {

    @Override
    public boolean supports(DomainEvent event) {
        return event instanceof ScheduledPaymentEvent;
    }

    @Override
    public SpecificRecord toAvro(DomainEvent event, EventEnvelope envelope) {
        ScheduledPaymentEvent scheduledPaymentEvent = (ScheduledPaymentEvent) event;
        return br.com.pixmanager.events.v1.ScheduledPaymentEvent.newBuilder()
                .setEnvelope(envelope)
                .setScheduledPaymentId(scheduledPaymentEvent.scheduledPaymentId())
                .setOccurrenceId(scheduledPaymentEvent.occurrenceId())
                .setPayerAccountId(scheduledPaymentEvent.payerAccountId())
                .setStatus(scheduledPaymentEvent.status())
                .setDueDate(scheduledPaymentEvent.dueDate())
                .setAmount(scheduledPaymentEvent.amount().value())
                .setNextAttemptAt(scheduledPaymentEvent.nextAttemptAt())
                .setCustomerNotificationRequired(scheduledPaymentEvent.customerNotificationRequired())
                .setPaymentOrderId(scheduledPaymentEvent.paymentOrderId())
                .build();
    }
}
