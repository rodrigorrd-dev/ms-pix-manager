package br.com.pixmanager.core.domain.entity.event;

import br.com.pixmanager.core.domain.entity.Refund;

/** {@code pix.refund.v1}: {@code RefundRequested}, {@code RefundReserved} (o spi-gateway então emite o pacs.004), {@code RefundSettled}, {@code RefundFailed}. */
public record RefundEvent(String eventType, Refund refund) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "refund";
    }

    @Override
    public String aggregateId() {
        return refund.id().toString();
    }
}
