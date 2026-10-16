package br.com.pixmanager.core.domain.entity.event;

import br.com.pixmanager.core.domain.entity.MedCase;
import br.com.pixmanager.core.domain.entity.Money;

import java.time.Instant;

/**
 * {@code pix.med.v1}. {@code action}: OPENED, ACCEPTED, REFUSED, CANCELLED, BLOCK_APPLIED, BLOCK_REFUSED, REFUND_REQUESTED, REFUND_SETTLED,
 * REFUND_FAILED, CLOSED, CONTESTATION_OPENED, CONTESTATION_DECIDED.
 */
public record MedCaseEvent(String action, MedCase medCase, Money refundedAmount, Instant contestationDeadlineAt) implements DomainEvent {

    public MedCaseEvent(String action, MedCase medCase) {
        this(action, medCase, null, null);
    }

    @Override
    public String aggregateType() {
        return "med";
    }

    @Override
    public String aggregateId() {
        return medCase.id().toString();
    }

    @Override
    public String eventType() {
        StringBuilder type = new StringBuilder("MedCase");
        for (String part : action.split("_")) {
            type.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return type.toString();
    }
}
