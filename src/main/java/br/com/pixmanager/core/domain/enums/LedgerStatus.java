package br.com.pixmanager.core.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/** Reserva em duas fases no core: {@code reservar → confirmar | cancelar} (decisão A2). */
public enum LedgerStatus {
    PENDING, RESERVED, CONFIRMED, CANCELLED, FAILED;

    public boolean canTransitionTo(LedgerStatus target) {
        return allowed().contains(target);
    }

    public boolean isFinal() {
        return this == CONFIRMED || this == CANCELLED || this == FAILED;
    }

    private Set<LedgerStatus> allowed() {
        return switch (this) {
            case PENDING -> EnumSet.of(RESERVED, CANCELLED, FAILED);
            case RESERVED -> EnumSet.of(CONFIRMED, CANCELLED);
            case CONFIRMED, CANCELLED, FAILED -> EnumSet.noneOf(LedgerStatus.class);
        };
    }
}
