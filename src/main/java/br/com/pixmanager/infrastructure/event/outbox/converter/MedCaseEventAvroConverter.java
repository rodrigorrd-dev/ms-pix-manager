package br.com.pixmanager.infrastructure.event.outbox.converter;

import br.com.pixmanager.core.domain.entity.MedCase;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.core.domain.entity.event.MedCaseEvent;
import br.com.pixmanager.events.v1.EventEnvelope;
import br.com.pixmanager.infrastructure.event.outbox.EventAvroConverter;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

/** {@code pix.med.v1}. Sem texto livre nem dados do pagador/recebedor: só ids, valores e estado. */
@Component
public class MedCaseEventAvroConverter implements EventAvroConverter {

    @Override
    public boolean supports(DomainEvent event) {
        return event instanceof MedCaseEvent;
    }

    @Override
    public SpecificRecord toAvro(DomainEvent event, EventEnvelope envelope) {
        MedCaseEvent e = (MedCaseEvent) event;
        MedCase c = e.medCase();
        return br.com.pixmanager.events.v1.MedCaseEvent.newBuilder()
                .setEnvelope(envelope)
                .setMedCaseId(c.id())
                .setParentCaseId(null)
                .setEndToEndId(c.endToEndId())
                .setLayer(c.layer())
                .setStatus(c.status().name())
                .setAction(e.action())
                .setTrackedAmount(c.trackedAmount() == null ? null : c.trackedAmount().value())
                .setBlockedAmount(c.blockedAmount() == null ? null : c.blockedAmount().value())
                .setRefundedAmount(e.refundedAmount() == null ? null : e.refundedAmount().value())
                .setWindowEndsAt(null)
                .setContestationDeadlineAt(e.contestationDeadlineAt())
                .build();
    }
}
