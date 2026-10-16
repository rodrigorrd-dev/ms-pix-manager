package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.LedgerReservation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerReservationPersistencePortOut {

    LedgerReservation save(LedgerReservation reservation);

    Optional<LedgerReservation> findById(UUID id);

    Optional<LedgerReservation> findByIdempotencyKey(String idempotencyKey);

    /** Para o sweeper: PENDING (ou RESERVED sem decisão) com {@code next_attempt_at} vencido; {@code SKIP LOCKED}. */
    List<LedgerReservation> findDue(Instant now, int limit);
}
