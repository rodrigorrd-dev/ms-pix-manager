package br.com.pixmanager.shared.mappers;

import br.com.pixmanager.api.rest.refund.dto.response.RefundResponse;
import br.com.pixmanager.core.domain.entity.Refund;
import org.springframework.stereotype.Component;

@Component
public class RefundMapper {

    public RefundResponse toResponse(Refund r) {
        return new RefundResponse(r.clientRefundId(), r.rtrId(), r.amount().toBcb(), r.nature(), r.description(),
                new RefundResponse.Times(r.requestedAt(), r.settledAt()), r.status(), r.statusReason());
    }
}
