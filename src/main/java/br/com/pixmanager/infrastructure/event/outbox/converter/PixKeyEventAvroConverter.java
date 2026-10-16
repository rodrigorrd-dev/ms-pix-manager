package br.com.pixmanager.infrastructure.event.outbox.converter;

import br.com.pixmanager.events.v1.EventEnvelope;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.event.PixKeyEvent;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.infrastructure.event.outbox.EventAvroConverter;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

/** Sem valor da chave em claro: só máscara e hash (índice cego). */
@Component
public class PixKeyEventAvroConverter implements EventAvroConverter {

    private final CryptoService crypto;

    PixKeyEventAvroConverter(CryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public boolean supports(DomainEvent event) {
        return event instanceof PixKeyEvent;
    }

    @Override
    public SpecificRecord toAvro(DomainEvent event, EventEnvelope envelope) {
        PixKey key = ((PixKeyEvent) event).key();
        return br.com.pixmanager.events.v1.PixKeyEvent.newBuilder()
                .setEnvelope(envelope)
                .setPixKeyId(key.id())
                .setAccountId(key.accountId())
                .setPixKeyType(key.value().type().name())
                .setPixKeyMasked(key.value().masked())
                .setPixKeyHash(crypto.blindIndex("key:" + key.value().value()))
                .setStatus(key.status().name())
                .setDeletedReason(key.deletedReason() == null ? null : key.deletedReason().name())
                .build();
    }
}
