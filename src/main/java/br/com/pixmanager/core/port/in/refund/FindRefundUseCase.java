package br.com.pixmanager.core.port.in.refund;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Refund;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FindRefundUseCase {

    Optional<Refund> find(Caller caller, String endToEndId, String clientRefundId);

    /** Devoluções de um Pix (campo {@code devolucoes} do {@code GET /pix/{e2eid}}); só se o Pix é do chamador. */
    List<Refund> listByReceived(Caller caller, UUID receivedId, UUID receiverAccountId);
}
