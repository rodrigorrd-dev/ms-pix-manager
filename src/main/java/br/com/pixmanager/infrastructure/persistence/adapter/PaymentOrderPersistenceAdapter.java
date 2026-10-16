package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.infrastructure.persistence.adapter.mapper.PaymentOrderEntityMapper;
import br.com.pixmanager.infrastructure.persistence.entity.PaymentOrderEntity;
import br.com.pixmanager.infrastructure.persistence.repository.PaymentOrderRepository;
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
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentOrderPersistenceAdapter implements PaymentOrderPersistencePortOut {

    private final PaymentOrderRepository repository;
    private final PaymentOrderEntityMapper mapper;

    public PaymentOrderPersistenceAdapter(PaymentOrderRepository repository, PaymentOrderEntityMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public PaymentOrder save(PaymentOrder order) {
        Optional<PaymentOrderEntity> existing = repository.findById(order.id());
        boolean isNew = existing.isEmpty();
        PaymentOrderEntity entity = existing.orElseGet(() -> new PaymentOrderEntity(order.id()));
        if (!isNew && entity.getVersion() != order.version()) {
            throw new PreconditionFailedException("A ordem foi alterada por outra requisição (versão desatualizada).");
        }
        mapper.apply(order, entity, isNew);
        try {
            return mapper.toDomain(repository.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException e) {
            throw new PreconditionFailedException("A ordem foi alterada por outra requisição (versão desatualizada).");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentOrder> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentOrder> findByEndToEndId(String endToEndId) {
        return repository.findByEndToEndId(endToEndId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PaymentOrder> list(UUID payerAccountId, Instant from, Instant to, PageQuery page) {
        Page<PaymentOrderEntity> result = repository.findByPayerAccountIdAndRequestedAtBetween(payerAccountId, from, to,
                PageRequest.of(page.page(), page.size(), Sort.by(Sort.Direction.DESC, "requestedAt")));
        return new PageResult<>(result.getContent().stream().map(mapper::toDomain).toList(), page.page(), page.size(), result.getTotalElements());
    }
}
