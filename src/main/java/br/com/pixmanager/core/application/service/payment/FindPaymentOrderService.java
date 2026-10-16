package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.port.in.payment.FindPaymentOrderUseCase;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FindPaymentOrderService implements FindPaymentOrderUseCase {

    private final PaymentOrderPersistencePortOut persistence;

    public FindPaymentOrderService(PaymentOrderPersistencePortOut persistence) {
        this.persistence = persistence;
    }

    @Override
    public Optional<PaymentOrder> find(Caller caller, UUID id) {
        return persistence.findById(id).filter(o -> caller.canAccess(o.payerAccountId()));
    }

    @Override
    public PageResult<PaymentOrder> list(Caller caller, UUID payerAccountId, Instant from, Instant to, PageQuery page) {
        if (!caller.canAccess(payerAccountId)) {
            return new PageResult<>(List.of(), page.page(), page.size(), 0);      // conta alheia: vazio, sem vazar existência
        }
        return persistence.list(payerAccountId, from, to, page);
    }
}
