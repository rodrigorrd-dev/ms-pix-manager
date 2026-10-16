package br.com.pixmanager.core.domain.entity.event;

import br.com.pixmanager.core.domain.entity.PaymentOrder;

/**
 * {@code pix.payment.v1}: {@code PaymentOrderCreated}, {@code PaymentReserved} (o spi-gateway então emite o pacs.008),
 * {@code PaymentSent}, {@code PaymentSettled}, {@code PaymentRejected}, {@code PaymentCancelled}.
 */
public record PaymentOrderEvent(String eventType, PaymentOrder order) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "payment";
    }

    @Override
    public String aggregateId() {
        return order.id().toString();
    }
}
