package br.com.pixmanager.core.port.in.payment;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface FindPaymentOrderUseCase {

    Optional<PaymentOrder> find(Caller caller, UUID id);

    PageResult<PaymentOrder> list(Caller caller, UUID payerAccountId, Instant from, Instant to, PageQuery page);
}
