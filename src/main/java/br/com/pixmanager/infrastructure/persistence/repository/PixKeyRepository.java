package br.com.pixmanager.infrastructure.persistence.repository;

import br.com.pixmanager.infrastructure.persistence.entity.PixKeyEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PixKeyRepository extends JpaRepository<PixKeyEntity, UUID> {

    Optional<PixKeyEntity> findFirstByKeyHashAndStatusNotIn(String keyHash, Collection<String> statuses);

    long countByAccountIdAndStatusNotIn(UUID accountId, Collection<String> statuses);

    Page<PixKeyEntity> findByAccountId(UUID accountId, Pageable pageable);

    Page<PixKeyEntity> findByAccountIdAndStatus(UUID accountId, String status, Pageable pageable);

    List<PixKeyEntity> findByStatusAndUpdatedAtBefore(String status, Instant before, Pageable pageable);
}
