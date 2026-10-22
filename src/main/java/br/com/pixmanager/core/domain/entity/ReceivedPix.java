package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.ComponentType;
import br.com.pixmanager.core.domain.enums.ReconciliationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pix recebido (liquidado no SPI). Identificado pelo {@code endToEndId} (idempotência: BR-PIX-01). O valor devolvível nasce
 * igual ao valor do Pix (BR-REF-04); {@code refundedTotal} só muda por UPDATE condicional atômico (módulo de devolução).
 */
public record ReceivedPix(UUID id, EndToEndId endToEndId, String txid, UUID chargeId, UUID receiverAccountId, Money amount,
                          Money refundableAmount, Money refundedTotal, PixPayer payer, String payerInfo, Instant settledAt,
                          ReconciliationStatus reconciliationStatus, String sourceMessageId, List<ValueComponent> components,
                          long version, Instant createdAt) {

    public ReceivedPix {
        components = components == null ? List.of() : List.copyOf(components);
        if (amount.isNegative()) {
            throw new IllegalArgumentException("valor do Pix não pode ser negativo");
        }
    }

    /** {@code valor = original + saque + troco + multa + juros − abatimento − desconto} (API Pix 2.10.0). */
    public static Money totalOf(List<ValueComponent> components) {
        Money total = Money.ZERO;
        for (ValueComponent valueComponent : components) {
            total = valueComponent.type().isDeduction() ? total.minus(valueComponent.amount()) : total.plus(valueComponent.amount());
        }
        return total;
    }

    public static ReceivedPix receive(UUID id, EndToEndId e2e, String txid, UUID receiverAccountId, Money amount, PixPayer payer,
                                      String payerInfo, Instant settledAt, String sourceMessageId, List<ValueComponent> components,
                                      Instant now) {
        List<ValueComponent> effective = components == null || components.isEmpty()
                ? List.of(new ValueComponent(ComponentType.ORIGINAL, amount, null, null)) : components;
        return new ReceivedPix(id, e2e, txid, null, receiverAccountId, amount, amount, Money.ZERO, payer, payerInfo, settledAt,
                ReconciliationStatus.NO_CHARGE, sourceMessageId, effective, 0, now);
    }

    public ReceivedPix reconciled(ReconciliationStatus status, UUID chargeId) {
        return new ReceivedPix(id, endToEndId, txid, chargeId, receiverAccountId, amount, refundableAmount, refundedTotal, payer,
                payerInfo, settledAt, status, sourceMessageId, components, version, createdAt);
    }

    /** Divergência entre o valor informado e a composição: o crédito segue (o SPI liquidou), mas vira alerta. */
    public boolean componentsMatchAmount() {
        return totalOf(components).equals(amount);
    }

    public Money refundable() {
        return refundableAmount.minus(refundedTotal);
    }
}
