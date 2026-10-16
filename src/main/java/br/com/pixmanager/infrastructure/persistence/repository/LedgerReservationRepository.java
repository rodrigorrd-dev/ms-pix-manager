package br.com.pixmanager.infrastructure.persistence.repository;

import br.com.pixmanager.infrastructure.persistence.entity.LedgerReservationEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerReservationRepository extends JpaRepository<LedgerReservationEntity, UUID> {

    Optional<LedgerReservationEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("select r from LedgerReservationEntity r where r.status = 'PENDING' and r.nextAttemptAt <= :now order by r.nextAttemptAt")
    List<LedgerReservationEntity> findDuePending(@Param("now") Instant now, Pageable pageable);
}
