package br.com.pixmanager.core.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/** {@code CREATED → VALIDATED → RESERVED → SENT_TO_SPI → SETTLED} ou {@code REJECTED | FAILED_TECHNICAL | CANCELLED} (BR-PAY-06). */
public enum PaymentStatus {
    CREATED, VALIDATED, RESERVED, SENT_TO_SPI, SETTLED, REJECTED, FAILED_TECHNICAL, CANCELLED;

    public boolean canTransitionTo(PaymentStatus target) {
        return allowed().contains(target);
    }

    public boolean isFinal() {
        return this == SETTLED || this == REJECTED || this == FAILED_TECHNICAL || this == CANCELLED;
    }

    /** O dinheiro já saiu para o SPI: não dá mais para cancelar. */
    public boolean isCancellable() {
        return this == CREATED || this == VALIDATED || this == RESERVED;
    }

    private Set<PaymentStatus> allowed() {
        return switch (this) {
            case CREATED -> EnumSet.of(VALIDATED, REJECTED, FAILED_TECHNICAL, CANCELLED);
            case VALIDATED -> EnumSet.of(RESERVED, REJECTED, FAILED_TECHNICAL, CANCELLED);
            case RESERVED -> EnumSet.of(SENT_TO_SPI, SETTLED, REJECTED, CANCELLED);
            case SENT_TO_SPI -> EnumSet.of(SETTLED, REJECTED);
            case SETTLED, REJECTED, FAILED_TECHNICAL, CANCELLED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }
}
