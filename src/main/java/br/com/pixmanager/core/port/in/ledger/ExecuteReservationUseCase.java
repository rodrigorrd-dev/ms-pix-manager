package br.com.pixmanager.core.port.in.ledger;

import br.com.pixmanager.core.domain.entity.LedgerReservation;

import java.util.UUID;

/** Orquestra reservar/confirmar/cancelar chamando o core FORA de transação; cada mudança de estado persiste sozinha. */
public interface ExecuteReservationUseCase {

    LedgerReservation find(UUID reservationId);

    /** PENDING → RESERVED (ou CANCELLED/FAILED se o core recusar). Idempotente. */
    LedgerReservation reserve(UUID reservationId);

    /**
     * Igual a {@link #reserve}, mas devolve o novo estado SEM persistir (a falha técnica, com backoff, é persistida e relançada).
     * Quem chama grava via {@link #save} na MESMA transação do efeito no fluxo dono.
     */
    LedgerReservation decideReserve(UUID reservationId);

    LedgerReservation save(LedgerReservation reservation);

    /** RESERVED → CONFIRMED (liquidado no SPI). Idempotente. */
    LedgerReservation confirm(String idempotencyKey);

    /** PENDING|RESERVED → CANCELLED (rejeitado/expirado). Idempotente. */
    LedgerReservation cancel(String idempotencyKey);
}
