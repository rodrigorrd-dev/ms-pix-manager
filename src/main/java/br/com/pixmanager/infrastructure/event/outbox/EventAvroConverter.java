package br.com.pixmanager.infrastructure.event.outbox;

import br.com.pixmanager.events.v1.EventEnvelope;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import org.apache.avro.specific.SpecificRecord;

/** Cada módulo registra um conversor {@code DomainEvent → Avro} como bean; o publicador escolhe o que {@link #supports}. */
public interface EventAvroConverter {

    boolean supports(DomainEvent event);

    SpecificRecord toAvro(DomainEvent event, EventEnvelope envelope);
}
