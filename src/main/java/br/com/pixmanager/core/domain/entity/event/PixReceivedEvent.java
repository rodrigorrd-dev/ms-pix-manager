package br.com.pixmanager.core.domain.entity.event;

import br.com.pixmanager.core.domain.entity.ReceivedPix;

/** Fato publicado em {@code pix.received.v1} (consumido por webhook, BI, contábil). */
public record PixReceivedEvent(ReceivedPix pix) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "received";
    }

    @Override
    public String aggregateId() {
        return pix.endToEndId().value();
    }

    @Override
    public String eventType() {
        return "PixReceived";
    }
}
