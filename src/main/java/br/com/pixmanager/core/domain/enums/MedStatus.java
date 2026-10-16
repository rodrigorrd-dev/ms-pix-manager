package br.com.pixmanager.core.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * {@code OPEN → ACCEPTED → REFUNDING → CLOSED}, ou {@code REFUSED | CANCELLED}. {@code CLOSED → CONTESTED} quando o recebedor contesta a
 * devolução e a contestação é aceita. {@code REFUNDING → ACCEPTED} se a devolução não se realiza.
 */
public enum MedStatus {
    OPEN, ACCEPTED, REFUNDING, CLOSED, REFUSED, CANCELLED, CONTESTED;

    public boolean canTransitionTo(MedStatus target) {
        return allowed().contains(target);
    }

    public boolean isFinal() {
        return this == CLOSED || this == REFUSED || this == CANCELLED || this == CONTESTED;
    }

    private Set<MedStatus> allowed() {
        return switch (this) {
            case OPEN -> EnumSet.of(ACCEPTED, REFUSED, CANCELLED, CLOSED);
            case ACCEPTED -> EnumSet.of(REFUNDING, CANCELLED);
            case REFUNDING -> EnumSet.of(CLOSED, ACCEPTED);
            case CLOSED -> EnumSet.of(CONTESTED);
            case REFUSED, CANCELLED, CONTESTED -> EnumSet.noneOf(MedStatus.class);
        };
    }
}
