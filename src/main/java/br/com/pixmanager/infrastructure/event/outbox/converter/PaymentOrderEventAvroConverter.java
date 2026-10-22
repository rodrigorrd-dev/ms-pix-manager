package br.com.pixmanager.infrastructure.event.outbox.converter;

import br.com.pixmanager.shared.model.document.Document;
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
        PaymentOrder paymentOrder = ((PaymentOrderEvent) event).order();
        return br.com.pixmanager.events.v1.PaymentOrderEvent.newBuilder()
                .setEnvelope(envelope)
                .setPaymentOrderId(paymentOrder.id())
                .setPayerAccountId(paymentOrder.payerAccountId())
                .setStatus(paymentOrder.status().name())
                .setAmount(paymentOrder.amount().value())
                .setEndToEndId(paymentOrder.endToEndId().value())
                .setRejectReasonCode(paymentOrder.rejectReasonCode())
                .setDestinationDocumentMasked(Document.maskedOrNull(paymentOrder.destination().document()))
                .build();
    }
}
