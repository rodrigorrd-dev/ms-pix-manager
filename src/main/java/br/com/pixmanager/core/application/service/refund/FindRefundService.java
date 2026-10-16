package br.com.pixmanager.core.application.service.refund;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.port.in.refund.FindRefundUseCase;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.RefundPersistencePortOut;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FindRefundService implements FindRefundUseCase {

    private final RefundPersistencePortOut refunds;
    private final ReceivedPixPersistencePortOut receivedPix;

    public FindRefundService(RefundPersistencePortOut refunds, ReceivedPixPersistencePortOut receivedPix) {
        this.refunds = refunds;
        this.receivedPix = receivedPix;
    }

    @Override
    public Optional<Refund> find(Caller caller, String endToEndId, String clientRefundId) {
        return receivedPix.findByEndToEndId(endToEndId)
                .filter(p -> caller.canAccess(p.receiverAccountId()))
                .flatMap(p -> refunds.findByEndToEndIdAndClientId(endToEndId, clientRefundId));
    }

    @Override
    public List<Refund> listByReceived(Caller caller, UUID receivedId, UUID receiverAccountId) {
        return caller.canAccess(receiverAccountId) ? refunds.findByReceivedId(receivedId) : List.of();
    }
}
