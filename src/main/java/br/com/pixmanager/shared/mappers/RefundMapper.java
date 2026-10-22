package br.com.pixmanager.shared.mappers;

import br.com.pixmanager.api.rest.refund.dto.response.RefundResponse;
import br.com.pixmanager.core.domain.entity.Refund;
import org.springframework.stereotype.Component;

@Component
public class RefundMapper {

    public RefundResponse toResponse(Refund refund) {
        return new RefundResponse(refund.clientRefundId(), refund.rtrId(), refund.amount().toBcb(), refund.nature(), refund.description(),
                new RefundResponse.Times(refund.requestedAt(), refund.settledAt()), refund.status(), refund.statusReason());
    }
}
