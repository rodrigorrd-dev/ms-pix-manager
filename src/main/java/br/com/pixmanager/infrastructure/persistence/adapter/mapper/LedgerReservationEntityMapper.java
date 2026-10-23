package br.com.pixmanager.infrastructure.persistence.adapter.mapper;

import br.com.pixmanager.shared.util.Text;
import br.com.pixmanager.infrastructure.persistence.entity.LedgerReservationEntity;

import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.enums.LedgerStatus;
import br.com.pixmanager.core.domain.entity.Money;

import java.time.Instant;

public final class LedgerReservationEntityMapper {

    private LedgerReservationEntityMapper() {}

    public static LedgerReservation toDomain(LedgerReservationEntity entity) {
        return new LedgerReservation(entity.getId(), entity.getIdempotencyKey(), entity.getAccountId(), LedgerPurpose.valueOf(entity.getPurpose()),
                entity.getReferenceType(), entity.getReferenceId(), Money.of(entity.getAmount()), LedgerStatus.valueOf(entity.getStatus()),
                entity.getLedgerRef(), entity.getAttempts(), entity.getNextAttemptAt(), entity.getLastError(), entity.getVersion());
    }

    /** Campos imutáveis só são gravados na criação; o resto é a transição de estado. */
    public static void apply(LedgerReservation reservation, LedgerReservationEntity entity, Instant now, boolean isNew) {
        if (isNew) {
            entity.setIdempotencyKey(reservation.idempotencyKey());
            entity.setAccountId(reservation.accountId());
            entity.setPurpose(reservation.purpose().name());
            entity.setReferenceType(reservation.referenceType());
            entity.setReferenceId(reservation.referenceId());
            entity.setAmount(reservation.amount().value());
        }
        entity.setStatus(reservation.status().name());
        entity.setLedgerRef(reservation.ledgerRef());
        entity.setAttempts((short) reservation.attempts());
        entity.setNextAttemptAt(reservation.nextAttemptAt());
        entity.setLastError(Text.truncate(reservation.lastError(), 500));
        entity.setUpdatedAt(now);
    }
}
