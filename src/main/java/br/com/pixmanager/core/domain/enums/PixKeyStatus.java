package br.com.pixmanager.core.domain.enums;

import java.util.EnumSet;
import java.util.Set;

public enum PixKeyStatus {
    PENDING_OWNERSHIP_PROOF, PENDING_DICT, ACTIVE, PORTABILITY_REQUESTED, OWNERSHIP_CLAIMED, REJECTED, DELETED;

    public boolean canTransitionTo(PixKeyStatus target) {
        return allowed().contains(target);
    }

    /** Ocupa o valor da chave no sistema (índice único parcial: fora DELETED/REJECTED). */
    public boolean isLive() {
        return this != DELETED && this != REJECTED;
    }

    private Set<PixKeyStatus> allowed() {
        return switch (this) {
            case PENDING_OWNERSHIP_PROOF -> EnumSet.of(PENDING_DICT, DELETED, REJECTED);
            case PENDING_DICT -> EnumSet.of(ACTIVE, REJECTED, DELETED);
            case ACTIVE -> EnumSet.of(PORTABILITY_REQUESTED, OWNERSHIP_CLAIMED, DELETED);
            case PORTABILITY_REQUESTED, OWNERSHIP_CLAIMED -> EnumSet.of(ACTIVE, DELETED);
            case REJECTED, DELETED -> EnumSet.noneOf(PixKeyStatus.class);
        };
    }
}
