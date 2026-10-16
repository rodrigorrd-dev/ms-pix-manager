package br.com.pixmanager.core.domain.entity.event;

import br.com.pixmanager.core.domain.entity.Charge;

import br.com.pixmanager.core.domain.entity.event.DomainEvent;

/** Fato publicado em {@code pix.charge.v1}: {@code ChargeCreated}, {@code ChargeRevised}, {@code ChargePaid}, {@code ChargeRemoved}. */
public record ChargeEvent(String eventType, Charge charge) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "charge";
    }

    @Override
    public String aggregateId() {
        return charge.id().toString();
    }
}
