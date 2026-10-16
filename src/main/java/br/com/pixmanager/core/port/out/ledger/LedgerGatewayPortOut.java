package br.com.pixmanager.core.port.out.ledger;

import br.com.pixmanager.core.domain.entity.LedgerReservation;

/**
 * API do core bancário (HTTP, idempotente por {@code endToEndId}). Falha TÉCNICA (timeout/5xx/429) ⇒
 * {@code IntegrationException}; resposta de NEGÓCIO (saldo, conta bloqueada) ⇒ {@link GatewayResult}. Nunca o contrário.
 */
public interface LedgerGatewayPortOut {

    GatewayResult reserve(LedgerReservation reservation);

    GatewayResult confirm(LedgerReservation reservation);

    GatewayResult cancel(LedgerReservation reservation);

    sealed interface GatewayResult {
        record Done(String ledgerRef) implements GatewayResult {}

        record Refused(Reason reason, String detail) implements GatewayResult {}

        enum Reason { INSUFFICIENT_FUNDS, ACCOUNT_BLOCKED, OTHER }
    }
}
