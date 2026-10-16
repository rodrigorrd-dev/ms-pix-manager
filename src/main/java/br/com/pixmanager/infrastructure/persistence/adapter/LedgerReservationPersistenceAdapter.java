package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.infrastructure.persistence.entity.LedgerReservationEntity;
import br.com.pixmanager.infrastructure.persistence.adapter.mapper.LedgerReservationEntityMapper;
import br.com.pixmanager.infrastructure.persistence.repository.LedgerReservationRepository;

import br.com.pixmanager.core.port.out.persistence.LedgerReservationPersistencePortOut;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class LedgerReservationPersistenceAdapter implements LedgerReservationPersistencePortOut {

    private final LedgerReservationRepository repository;
    private final Clock clock;

    public LedgerReservationPersistenceAdapter(LedgerReservationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public LedgerReservation save(LedgerReservation reservation) {
        Optional<LedgerReservationEntity> existing = repository.findById(reservation.id());
        boolean isNew = existing.isEmpty();
        LedgerReservationEntity entity = existing.orElseGet(() -> new LedgerReservationEntity(reservation.id()));
        LedgerReservationEntityMapper.apply(reservation, entity, clock.instant(), isNew);
        // saveAndFlush: o conflito otimista aparece aqui (e não no commit, fora de qualquer try)
        return LedgerReservationEntityMapper.toDomain(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LedgerReservation> findById(UUID id) {
        return repository.findById(id).map(LedgerReservationEntityMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LedgerReservation> findByIdempotencyKey(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey).map(LedgerReservationEntityMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerReservation> findDue(Instant now, int limit) {
        return repository.findDuePending(now, PageRequest.of(0, limit)).stream()
                .map(LedgerReservationEntityMapper::toDomain).toList();
    }
}
