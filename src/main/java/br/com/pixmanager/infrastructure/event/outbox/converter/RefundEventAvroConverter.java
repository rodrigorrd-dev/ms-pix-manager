package br.com.pixmanager.infrastructure.event.outbox.converter;

import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.core.domain.entity.event.RefundEvent;
import br.com.pixmanager.events.v1.EventEnvelope;
import br.com.pixmanager.infrastructure.event.outbox.EventAvroConverter;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

@Component
public class RefundEventAvroConverter implements EventAvroConverter {

    @Override
    public boolean supports(DomainEvent event) {
        return event instanceof RefundEvent;
    }

    @Override
    public SpecificRecord toAvro(DomainEvent event, EventEnvelope envelope) {
        Refund refund = ((RefundEvent) event).refund();
        return br.com.pixmanager.events.v1.RefundEvent.newBuilder()
                .setEnvelope(envelope)
                .setRefundId(refund.id())
                .setClientRefundId(refund.clientRefundId())
                .setEndToEndId(refund.endToEndId().value())
                .setRtrId(refund.rtrId())
                .setAmount(refund.amount().value())
                .setNature(refund.nature().name())
                .setStatus(refund.status().name())
                .setReason(refund.statusReason())
                .build();
    }
}
