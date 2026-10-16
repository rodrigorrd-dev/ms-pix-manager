package br.com.pixmanager.core.domain.enums;

import java.util.EnumSet;

/** Valores literais da API Pix 2.10.0 ({@code Devolucao.status}). */
public enum RefundStatus {
    EM_PROCESSAMENTO, DEVOLVIDO, NAO_REALIZADO;

    public boolean canTransitionTo(RefundStatus target) {
        return this == EM_PROCESSAMENTO && EnumSet.of(DEVOLVIDO, NAO_REALIZADO).contains(target);
    }

    public boolean isFinal() {
        return this != EM_PROCESSAMENTO;
    }
}
