package br.com.pixmanager.core.port.in.ledger;

import java.util.UUID;

/** Reserva no core (HTTP, fora de transação) e despacha o resultado ao fluxo dono da reserva. */
public interface ProcessReservationUseCase {

    void process(UUID reservationId);
}
