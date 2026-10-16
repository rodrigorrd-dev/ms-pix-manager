package br.com.pixmanager.infrastructure.persistence.repository;

import br.com.pixmanager.infrastructure.persistence.entity.PaymentOrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrderEntity, UUID> {

    Optional<PaymentOrderEntity> findByEndToEndId(String endToEndId);

    Page<PaymentOrderEntity> findByPayerAccountIdAndRequestedAtBetween(UUID payerAccountId, Instant from, Instant to, Pageable pageable);
}
