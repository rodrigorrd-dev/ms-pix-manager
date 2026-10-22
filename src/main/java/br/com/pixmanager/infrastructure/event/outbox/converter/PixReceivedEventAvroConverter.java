package br.com.pixmanager.infrastructure.event.outbox.converter;

import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.core.domain.entity.event.PixReceivedEvent;
import br.com.pixmanager.events.v1.EventEnvelope;
import br.com.pixmanager.infrastructure.event.outbox.EventAvroConverter;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.stereotype.Component;

/** {@code pix.received.v1}: sem CPF em claro — só máscara e índice cego do pagador. */
@Component
public class PixReceivedEventAvroConverter implements EventAvroConverter {

    private final CryptoService crypto;

    public PixReceivedEventAvroConverter(CryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public boolean supports(DomainEvent event) {
        return event instanceof PixReceivedEvent;
    }

    @Override
    public SpecificRecord toAvro(DomainEvent event, EventEnvelope envelope) {
        ReceivedPix receivedPix = ((PixReceivedEvent) event).pix();
        var payerDoc = receivedPix.payer().document();
        return br.com.pixmanager.events.v1.PixReceivedEvent.newBuilder()
                .setEnvelope(envelope)
                .setEndToEndId(receivedPix.endToEndId().value())
                .setTxid(receivedPix.txid())
                .setChargeId(receivedPix.chargeId())
                .setReceiverAccountId(receivedPix.receiverAccountId())
                .setAmount(receivedPix.amount().value())
                .setSettledAt(receivedPix.settledAt())
                .setPayerDocumentMasked(Document.maskedOrNull(payerDoc))
                .setPayerDocumentHash(payerDoc == null ? null : crypto.blindIndex(payerDoc.value()))
                .setReconciliationStatus(receivedPix.reconciliationStatus().name())
                .build();
    }
}
