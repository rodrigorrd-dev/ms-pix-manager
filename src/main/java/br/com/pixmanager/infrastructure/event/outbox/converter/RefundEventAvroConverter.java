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
        Refund r = ((RefundEvent) event).refund();
        return br.com.pixmanager.events.v1.RefundEvent.newBuilder()
                .setEnvelope(envelope)
                .setRefundId(r.id())
                .setClientRefundId(r.clientRefundId())
                .setEndToEndId(r.endToEndId().value())
                .setRtrId(r.rtrId())
                .setAmount(r.amount().value())
                .setNature(r.nature().name())
                .setStatus(r.status().name())
                .setReason(r.statusReason())
                .build();
    }
}
