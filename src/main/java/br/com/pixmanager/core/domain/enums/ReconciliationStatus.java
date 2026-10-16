package br.com.pixmanager.core.domain.enums;

/** Resultado da conciliação do Pix recebido com a cobrança (BR-PIX-02/03, BR-COB-10). */
public enum ReconciliationStatus {
    NO_CHARGE, MATCHED, MATCHED_AFTER_EXPIRATION, MISMATCH
}
