package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.domain.entity.AccountLimit;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.core.port.out.persistence.LimitPersistencePortOut;
import br.com.pixmanager.infrastructure.persistence.adapter.mapper.LimitEntityMapper;
import br.com.pixmanager.infrastructure.persistence.entity.LimitEntity;
import br.com.pixmanager.infrastructure.persistence.repository.LimitRepository;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class LimitPersistenceAdapter implements LimitPersistencePortOut {

    private final LimitRepository repository;
    private final LimitEntityMapper mapper;

    public LimitPersistenceAdapter(LimitRepository repository, LimitEntityMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountLimit> findByAccount(UUID accountId) {
        return repository.findByAccountId(accountId).stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountLimit> find(UUID accountId, LimitScope scope) {
        return repository.findByAccountIdAndScope(accountId, scope.name()).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public AccountLimit save(AccountLimit limit) {
        Optional<LimitEntity> existing = repository.findById(limit.id());
        boolean isNew = existing.isEmpty();
        LimitEntity entity = existing.orElseGet(() -> new LimitEntity(limit.id()));
        if (!isNew && entity.getVersion() != limit.version()) {
            throw new PreconditionFailedException("O limite foi alterado por outra requisição (versão desatualizada).");
        }
        mapper.apply(limit, entity, isNew);
        try {
            return mapper.toDomain(repository.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException e) {
            throw new PreconditionFailedException("O limite foi alterado por outra requisição (versão desatualizada).");
        }
    }
}
