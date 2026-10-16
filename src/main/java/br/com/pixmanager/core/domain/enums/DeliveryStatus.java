package br.com.pixmanager.core.domain.enums;

public enum DeliveryStatus {
    PENDING, RETRYING, DELIVERED, DEAD;

    public boolean isFinal() {
        return this == DELIVERED || this == DEAD;
    }
}
