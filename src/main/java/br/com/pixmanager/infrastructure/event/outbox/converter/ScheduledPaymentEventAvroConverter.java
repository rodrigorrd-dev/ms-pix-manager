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
        ScheduledPaymentEvent e = (ScheduledPaymentEvent) event;
        return br.com.pixmanager.events.v1.ScheduledPaymentEvent.newBuilder()
                .setEnvelope(envelope)
                .setScheduledPaymentId(e.scheduledPaymentId())
                .setOccurrenceId(e.occurrenceId())
                .setPayerAccountId(e.payerAccountId())
                .setStatus(e.status())
                .setDueDate(e.dueDate())
                .setAmount(e.amount().value())
                .setNextAttemptAt(e.nextAttemptAt())
                .setCustomerNotificationRequired(e.customerNotificationRequired())
                .setPaymentOrderId(e.paymentOrderId())
                .build();
    }
}
