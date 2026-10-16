package br.com.pixmanager.core.domain.entity;

import java.util.UUID;

/** Resultado de tentar conciliar um Pix recebido com uma cobrança (BR-PIX-02/03, BR-COB-10). Valor, não exceção. */
public sealed interface ChargeSettlement {

    /** Sem cobrança com esse txid para o recebedor. */
    record NotFound() implements ChargeSettlement {}

    /** Pago no prazo e no valor: cobrança passou a CONCLUIDA. */
    record Settled(UUID chargeId) implements ChargeSettlement {}

    /** Pago depois de expirar: o Pix é aceito (o SPI já liquidou), a cobrança segue ATIVA até a regra de conciliação. */
    record PaidAfterExpiration(UUID chargeId) implements ChargeSettlement {}

    /** Valor diferente do esperado: não bloqueia o crédito; alerta de conciliação. */
    record AmountMismatch(UUID chargeId) implements ChargeSettlement {}

    /** Cobrança já CONCLUIDA/REMOVIDA: o Pix vira "sem cobrança vinculada" + alerta. */
    record NotPayable(UUID chargeId) implements ChargeSettlement {}
}
