package br.com.pixmanager.core.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Status do REGISTRO da cobrança, valores literais da API Pix 2.10.0 (wire contract: renomear quebra clientes).
 * Expiração NÃO é status: cobrança expirada segue {@code ATIVA} (doc BCB) — BR-COB-07.
 */
public enum ChargeStatus {
    ATIVA, CONCLUIDA, REMOVIDA_PELO_USUARIO_RECEBEDOR, REMOVIDA_PELO_PSP;

    public boolean canTransitionTo(ChargeStatus target) {
        return allowed().contains(target);
    }

    public boolean isFinal() {
        return this != ATIVA;
    }

    private Set<ChargeStatus> allowed() {
        return switch (this) {
            case ATIVA -> EnumSet.of(CONCLUIDA, REMOVIDA_PELO_USUARIO_RECEBEDOR, REMOVIDA_PELO_PSP);
            case CONCLUIDA, REMOVIDA_PELO_USUARIO_RECEBEDOR, REMOVIDA_PELO_PSP -> EnumSet.noneOf(ChargeStatus.class);
        };
    }
}
