package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.LimitScope;

import java.time.LocalDate;

/** Resultado de consumir o limite: valor, não exceção (BR-LIM-06: estouro vira ordem REJECTED com PIX-422-050). */
public sealed interface LimitDecision {

    /** Consumido: {@code day} identifica o dia (America/Sao_Paulo) para o estorno (compensação) se o fluxo falhar depois. */
    record Allowed(LocalDate day, boolean deviceDailyConsumed, Money amount) implements LimitDecision {}

    record Rejected(LimitScope scope, Money limit) implements LimitDecision {}
}
