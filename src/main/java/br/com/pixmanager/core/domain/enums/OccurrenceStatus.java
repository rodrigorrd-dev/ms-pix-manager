package br.com.pixmanager.core.domain.enums;

public enum OccurrenceStatus {
    SCHEDULED, PROCESSING, EXECUTED, RETRY_PENDING, FAILED_INSUFFICIENT_FUNDS, FAILED, CANCELLED;

    public boolean isFinal() {
        return this == EXECUTED || this == FAILED_INSUFFICIENT_FUNDS || this == FAILED || this == CANCELLED;
    }

    /** BR-SCH-04: só cancela enquanto aguarda; {@code PROCESSING} não cancela. */
    public boolean isCancellable() {
        return this == SCHEDULED || this == RETRY_PENDING;
    }
}
