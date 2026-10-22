package br.com.pixmanager.infrastructure.event.outbox.converter;

import br.com.pixmanager.core.domain.entity.Money;
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
        AutomaticAuthorization authorization = ((AutomaticAuthorizationEvent) event).authorization();
        return br.com.pixmanager.events.v1.AutomaticAuthorizationEvent.newBuilder()
                .setEnvelope(envelope)
                .setAuthorizationId(authorization.id())
                .setIdRec(authorization.idRec())
                .setStatus(authorization.status())
                .setRequestStatus(authorization.requestStatus())
                .setRejectionCode(authorization.rejectionCode())
                .setCancelledBy(authorization.cancelledBy())
                .setPayerAccountId(authorization.payerAccountId())
                .setPayerMaxAmount(Money.valueOrNull(authorization.payerMaxAmount()))
                .setReceiverDocumentMasked(authorization.receiverDocument() == null ? null : Document.of(authorization.receiverDocument()).masked())
                .build();
    }
}
