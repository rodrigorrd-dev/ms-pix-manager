package br.com.pixmanager.infrastructure.persistence.repository;

import br.com.pixmanager.infrastructure.persistence.entity.LimitEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LimitRepository extends JpaRepository<LimitEntity, UUID> {

    List<LimitEntity> findByAccountId(UUID accountId);

    Optional<LimitEntity> findByAccountIdAndScope(UUID accountId, String scope);
}
