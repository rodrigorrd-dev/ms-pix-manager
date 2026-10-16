package br.com.pixmanager.core.domain.entity.event;

import br.com.pixmanager.core.domain.entity.PixKey;

import br.com.pixmanager.core.domain.entity.event.DomainEvent;

/** Fato publicado em {@code pix.key.v1}. */
public record PixKeyEvent(String eventType, PixKey key) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "key";
    }

    @Override
    public String aggregateId() {
        return key.id().toString();
    }
}
