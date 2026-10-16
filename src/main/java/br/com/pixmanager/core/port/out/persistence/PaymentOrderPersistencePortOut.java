package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderPersistencePortOut {

    PaymentOrder save(PaymentOrder order);

    Optional<PaymentOrder> findById(UUID id);

    Optional<PaymentOrder> findByEndToEndId(String endToEndId);

    PageResult<PaymentOrder> list(UUID payerAccountId, Instant from, Instant to, PageQuery page);
}
