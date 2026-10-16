package br.com.pixmanager.core.application.service.ledger;

import br.com.pixmanager.core.port.in.ledger.RequestReservationUseCase;
import br.com.pixmanager.core.port.out.persistence.LedgerReservationPersistencePortOut;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.domain.entity.Money;

import java.time.Clock;
import java.util.UUID;

public class RequestReservationService implements RequestReservationUseCase {

    private final LedgerReservationPersistencePortOut persistence;
    private final IdGeneratorPortOut ids;
    private final Clock clock;

    public RequestReservationService(LedgerReservationPersistencePortOut persistence, IdGeneratorPortOut ids, Clock clock) {
        this.persistence = persistence;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public LedgerReservation request(String idempotencyKey, UUID accountId, LedgerPurpose purpose, String referenceType,
                                     UUID referenceId, Money amount) {
        return persistence.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> persistence.save(LedgerReservation.pending(ids.newId(), idempotencyKey, accountId, purpose,
                        referenceType, referenceId, amount, clock.instant())));
    }
}
