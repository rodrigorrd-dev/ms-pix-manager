package br.com.pixmanager.infrastructure.event.outbox.converter;

import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.core.domain.entity.event.PaymentOrderEvent;
import br.com.pixmanager.events.v1.EventEnvelope;
import br.com.pixmanager.infrastructure.event.outbox.EventAvroConverter;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

/** {@code pix.payment.v1}: documento do favorecido só MASCARADO. */
@Component
public class PaymentOrderEventAvroConverter implements EventAvroConverter {

    @Override
    public boolean supports(DomainEvent event) {
        return event instanceof PaymentOrderEvent;
    }

    @Override
    public SpecificRecord toAvro(DomainEvent event, EventEnvelope envelope) {
        PaymentOrder o = ((PaymentOrderEvent) event).order();
        return br.com.pixmanager.events.v1.PaymentOrderEvent.newBuilder()
                .setEnvelope(envelope)
                .setPaymentOrderId(o.id())
                .setPayerAccountId(o.payerAccountId())
                .setStatus(o.status().name())
                .setAmount(o.amount().value())
                .setEndToEndId(o.endToEndId().value())
                .setRejectReasonCode(o.rejectReasonCode())
                .setDestinationDocumentMasked(o.destination().document() == null ? null : o.destination().document().masked())
                .build();
    }
}
