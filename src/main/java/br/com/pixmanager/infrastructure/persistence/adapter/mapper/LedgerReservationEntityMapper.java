package br.com.pixmanager.infrastructure.persistence.adapter.mapper;

import br.com.pixmanager.infrastructure.persistence.entity.LedgerReservationEntity;

import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.enums.LedgerStatus;
import br.com.pixmanager.core.domain.entity.Money;

import java.time.Instant;

public final class LedgerReservationEntityMapper {

    private LedgerReservationEntityMapper() {}

    public static LedgerReservation toDomain(LedgerReservationEntity e) {
        return new LedgerReservation(e.getId(), e.getIdempotencyKey(), e.getAccountId(), LedgerPurpose.valueOf(e.getPurpose()),
                e.getReferenceType(), e.getReferenceId(), Money.of(e.getAmount()), LedgerStatus.valueOf(e.getStatus()),
                e.getLedgerRef(), e.getAttempts(), e.getNextAttemptAt(), e.getLastError(), e.getVersion());
    }

    /** Campos imutáveis só são gravados na criação; o resto é a transição de estado. */
    public static void apply(LedgerReservation d, LedgerReservationEntity e, Instant now, boolean isNew) {
        if (isNew) {
            e.setIdempotencyKey(d.idempotencyKey());
            e.setAccountId(d.accountId());
            e.setPurpose(d.purpose().name());
            e.setReferenceType(d.referenceType());
            e.setReferenceId(d.referenceId());
            e.setAmount(d.amount().value());
        }
        e.setStatus(d.status().name());
        e.setLedgerRef(d.ledgerRef());
        e.setAttempts((short) d.attempts());
        e.setNextAttemptAt(d.nextAttemptAt());
        e.setLastError(d.lastError() == null ? null : d.lastError().substring(0, Math.min(500, d.lastError().length())));
        e.setUpdatedAt(now);
    }
}
