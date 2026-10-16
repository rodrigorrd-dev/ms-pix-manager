package br.com.pixmanager.core.port.in.ledger;

import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;

/**
 * Reação de cada fluxo (pagamento, devolução, MED) ao resultado da reserva no core. Roda em TRANSAÇÃO: atualiza o
 * agregado e grava o evento na outbox atomicamente.
 */
public interface ReservationOutcomeHandler {

    LedgerPurpose purpose();

    /** Reserva efetivada no core: o fluxo segue (ex.: publicar evento para o spi-gateway emitir o pacs.*). */
    void onReserved(LedgerReservation reservation);

    /** Core recusou (saldo/conta bloqueada): o fluxo falha em definitivo; {@code lastError} traz o motivo. */
    void onRefused(LedgerReservation reservation);
}
