package br.com.pixmanager.infrastructure.persistence.repository;

import br.com.pixmanager.infrastructure.persistence.entity.RefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<RefundEntity, UUID> {

    Optional<RefundEntity> findByEndToEndIdAndClientRefundId(String endToEndId, String clientRefundId);

    Optional<RefundEntity> findByRtrId(String rtrId);

    List<RefundEntity> findByReceivedIdOrderByRequestedAt(UUID receivedId);
}
