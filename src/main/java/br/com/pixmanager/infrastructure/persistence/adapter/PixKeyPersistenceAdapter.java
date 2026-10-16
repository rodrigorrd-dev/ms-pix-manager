package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import br.com.pixmanager.infrastructure.persistence.entity.PixKeyEntity;
import br.com.pixmanager.infrastructure.persistence.adapter.mapper.PixKeyEntityMapper;
import br.com.pixmanager.infrastructure.persistence.repository.PixKeyRepository;

import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class PixKeyPersistenceAdapter implements PixKeyPersistencePortOut {

    private static final Set<String> NOT_LIVE = Set.of(PixKeyStatus.DELETED.name(), PixKeyStatus.REJECTED.name());

    private final PixKeyRepository repository;
    private final PixKeyEntityMapper mapper;

    public PixKeyPersistenceAdapter(PixKeyRepository repository, PixKeyEntityMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public PixKey save(PixKey key) {
        Optional<PixKeyEntity> existing = repository.findById(key.id());
        boolean isNew = existing.isEmpty();
        PixKeyEntity entity = existing.orElseGet(() -> new PixKeyEntity(key.id()));
        if (!isNew && entity.getVersion() != key.version()) {
            throw new PreconditionFailedException("A chave foi alterada por outra requisição (versão desatualizada).");
        }
        mapper.apply(key, entity, isNew);
        try {
            return mapper.toDomain(repository.saveAndFlush(entity));   // flush aqui: o conflito aparece dentro do try
        } catch (OptimisticLockingFailureException e) {
            throw new PreconditionFailedException("A chave foi alterada por outra requisição (versão desatualizada).");
        } catch (DataIntegrityViolationException e) {
            // corrida entre dois registros do mesmo valor: o índice único parcial uk_pix_key_live_hash decide
            throw new ConflictException(ErrorCodes.CONFLICT, "A chave informada já está registrada.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PixKey> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PixKey> findLiveByValue(PixKeyValue value) {
        return repository.findFirstByKeyHashAndStatusNotIn(mapper.hashOf(value), NOT_LIVE).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public long countLiveByAccount(UUID accountId) {
        return repository.countByAccountIdAndStatusNotIn(accountId, NOT_LIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PixKey> list(UUID accountId, Optional<PixKeyStatus> status, PageQuery page) {
        PageRequest pageable = PageRequest.of(page.page(), page.size(), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PixKeyEntity> result = status.isPresent()
                ? repository.findByAccountIdAndStatus(accountId, status.get().name(), pageable)
                : repository.findByAccountId(accountId, pageable);
        return new PageResult<>(result.getContent().stream().map(mapper::toDomain).toList(), page.page(), page.size(),
                result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PixKey> findPendingDict(Instant olderThan, int limit) {
        return repository.findByStatusAndUpdatedAtBefore(PixKeyStatus.PENDING_DICT.name(), olderThan,
                        PageRequest.of(0, limit, Sort.by("updatedAt"))).stream()
                .map(mapper::toDomain).toList();
    }
}
