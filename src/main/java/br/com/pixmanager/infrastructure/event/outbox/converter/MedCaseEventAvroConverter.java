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
        MedCaseEvent medCaseEvent = (MedCaseEvent) event;
        MedCase medCase = medCaseEvent.medCase();
        return br.com.pixmanager.events.v1.MedCaseEvent.newBuilder()
                .setEnvelope(envelope)
                .setMedCaseId(medCase.id())
                .setParentCaseId(null)
                .setEndToEndId(medCase.endToEndId())
                .setLayer(medCase.layer())
                .setStatus(medCase.status().name())
                .setAction(medCaseEvent.action())
                .setTrackedAmount(medCase.trackedAmount() == null ? null : medCase.trackedAmount().value())
                .setBlockedAmount(medCase.blockedAmount() == null ? null : medCase.blockedAmount().value())
                .setRefundedAmount(medCaseEvent.refundedAmount() == null ? null : medCaseEvent.refundedAmount().value())
                .setWindowEndsAt(null)
                .setContestationDeadlineAt(medCaseEvent.contestationDeadlineAt())
                .build();
    }
}
