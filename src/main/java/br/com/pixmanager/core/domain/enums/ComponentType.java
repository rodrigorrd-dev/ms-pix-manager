package br.com.pixmanager.core.domain.enums;

/** Composição do valor do Pix: {@code valor = ORIGINAL + WITHDRAWAL + CHANGE + FINE + INTEREST − REBATE − DISCOUNT}. */
public enum ComponentType {
    ORIGINAL, WITHDRAWAL, CHANGE, FINE, INTEREST, REBATE, DISCOUNT;

    public boolean isDeduction() {
        return this == REBATE || this == DISCOUNT;
    }
}
