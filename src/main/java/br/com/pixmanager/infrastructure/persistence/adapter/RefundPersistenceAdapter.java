package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.port.out.persistence.RefundPersistencePortOut;
import br.com.pixmanager.infrastructure.persistence.adapter.mapper.RefundEntityMapper;
import br.com.pixmanager.infrastructure.persistence.entity.RefundEntity;
import br.com.pixmanager.infrastructure.persistence.repository.RefundRepository;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class RefundPersistenceAdapter implements RefundPersistencePortOut {

    private final RefundRepository repository;
    private final RefundEntityMapper mapper;

    public RefundPersistenceAdapter(RefundRepository repository, RefundEntityMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Refund save(Refund refund) {
        Optional<RefundEntity> existing = repository.findById(refund.id());
        boolean isNew = existing.isEmpty();
        RefundEntity entity = existing.orElseGet(() -> new RefundEntity(refund.id()));
        if (!isNew && entity.getVersion() != refund.version()) {
            throw new PreconditionFailedException("A devolução foi alterada por outra requisição (versão desatualizada).");
        }
        mapper.apply(refund, entity, isNew);
        try {
            return mapper.toDomain(repository.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException exception) {
            throw new PreconditionFailedException("A devolução foi alterada por outra requisição (versão desatualizada).");
        } catch (DataIntegrityViolationException exception) {
            // corrida entre duas devoluções com o mesmo id do cliente: o índice único decide
            throw new ConflictException(ErrorCodes.CONFLICT, "Já existe devolução com este identificador.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Refund> findByEndToEndIdAndClientId(String endToEndId, String clientRefundId) {
        return repository.findByEndToEndIdAndClientRefundId(endToEndId, clientRefundId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Refund> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Refund> findByRtrId(String rtrId) {
        return repository.findByRtrId(rtrId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Refund> findByReceivedId(UUID receivedId) {
        return repository.findByReceivedIdOrderByRequestedAt(receivedId).stream().map(mapper::toDomain).toList();
    }
}
