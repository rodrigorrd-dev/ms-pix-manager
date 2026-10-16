package br.com.pixmanager.core.port.in.ledger;

import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.entity.Money;

import java.util.UUID;

/** Grava a INTENÇÃO (PENDING) na transação do negócio; quem chama o core é o worker (nunca HTTP dentro de TX). */
public interface RequestReservationUseCase {

    LedgerReservation request(String idempotencyKey, UUID accountId, LedgerPurpose purpose, String referenceType,
                              UUID referenceId, Money amount);
}
