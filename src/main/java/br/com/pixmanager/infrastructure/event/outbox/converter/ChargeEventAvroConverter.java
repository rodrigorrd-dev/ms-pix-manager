package br.com.pixmanager.infrastructure.event.outbox.converter;

import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.core.domain.entity.event.ChargeEvent;
import br.com.pixmanager.events.v1.EventEnvelope;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.infrastructure.event.outbox.EventAvroConverter;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;

/** {@code ChargeEvent} (Avro, contracts/avro/charge-event.avsc): sem CPF — só o hash do devedor. */
@Component
public class ChargeEventAvroConverter implements EventAvroConverter {

    private final CryptoService crypto;

    ChargeEventAvroConverter(CryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public boolean supports(DomainEvent event) {
        return event instanceof ChargeEvent;
    }

    @Override
    public SpecificRecord toAvro(DomainEvent event, EventEnvelope envelope) {
        Charge c = ((ChargeEvent) event).charge();
        return br.com.pixmanager.events.v1.ChargeEvent.newBuilder()
                .setEnvelope(envelope)
                .setChargeId(c.id())
                .setTxid(c.txid().value())
                .setChargeType(c.type().name())
                .setStatus(c.status().name())
                .setRevision(c.revision())
                .setReceiverAccountId(c.receiverAccountId())
                .setOriginalAmount(c.originalAmount().value())
                .setExpiresAt(c.expiresAt())
                .setDueDate(c.dueDate())
                .setDebtorDocumentHash(c.debtor() == null ? null : crypto.blindIndex(c.debtor().document().value()))
                .build();
    }
}
