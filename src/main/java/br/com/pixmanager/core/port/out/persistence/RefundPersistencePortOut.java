package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.Refund;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundPersistencePortOut {

    Refund save(Refund refund);

    Optional<Refund> findById(UUID id);

    Optional<Refund> findByEndToEndIdAndClientId(String endToEndId, String clientRefundId);

    Optional<Refund> findByRtrId(String rtrId);

    List<Refund> findByReceivedId(UUID receivedId);
}
