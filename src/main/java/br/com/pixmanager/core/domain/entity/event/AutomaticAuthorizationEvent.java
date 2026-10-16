package br.com.pixmanager.core.domain.entity.event;

import br.com.pixmanager.core.domain.entity.AutomaticAuthorization;

/** {@code pix.automatic.v1}: Received, Approved, Rejected, Expired, Cancelled, LimitAdjusted. A chave da mensagem é o {@code idRec}. */
public record AutomaticAuthorizationEvent(String eventType, AutomaticAuthorization authorization) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "automatic";
    }

    @Override
    public String aggregateId() {
        return authorization.idRec();
    }
}
