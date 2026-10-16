package br.com.pixmanager.core.domain.enums;

/** Natureza da devolução (API Pix 2.10.0; códigos pacs.004: MD06, SL02, BE08, FR01). */
public enum RefundNature {
    ORIGINAL, RETIRADA, MED_OPERACIONAL, MED_FRAUDE, MED_PIX_AUTOMATICO;

    /** BR-REF-06: natureza MED só pelo fluxo MED. */
    public boolean isMed() {
        return this == MED_OPERACIONAL || this == MED_FRAUDE || this == MED_PIX_AUTOMATICO;
    }
}
