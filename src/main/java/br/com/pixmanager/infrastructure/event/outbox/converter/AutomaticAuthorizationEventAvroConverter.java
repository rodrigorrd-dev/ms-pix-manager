package br.com.pixmanager.infrastructure.event.outbox.converter;

import br.com.pixmanager.core.domain.entity.AutomaticAuthorization;
import br.com.pixmanager.core.domain.entity.event.AutomaticAuthorizationEvent;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.events.v1.EventEnvelope;
import br.com.pixmanager.infrastructure.event.outbox.EventAvroConverter;
import br.com.pixmanager.shared.model.document.Document;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

/** {@code pix.automatic.v1}: documento do recebedor só MASCARADO. */
@Component
public class AutomaticAuthorizationEventAvroConverter implements EventAvroConverter {

    @Override
    public boolean supports(DomainEvent event) {
        return event instanceof AutomaticAuthorizationEvent;
    }

    @Override
    public SpecificRecord toAvro(DomainEvent event, EventEnvelope envelope) {
        AutomaticAuthorization a = ((AutomaticAuthorizationEvent) event).authorization();
        return br.com.pixmanager.events.v1.AutomaticAuthorizationEvent.newBuilder()
                .setEnvelope(envelope)
                .setAuthorizationId(a.id())
                .setIdRec(a.idRec())
                .setStatus(a.status())
                .setRequestStatus(a.requestStatus())
                .setRejectionCode(a.rejectionCode())
                .setCancelledBy(a.cancelledBy())
                .setPayerAccountId(a.payerAccountId())
                .setPayerMaxAmount(a.payerMaxAmount() == null ? null : a.payerMaxAmount().value())
                .setReceiverDocumentMasked(a.receiverDocument() == null ? null : Document.of(a.receiverDocument()).masked())
                .build();
    }
}
