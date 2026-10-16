package br.com.pixmanager.infrastructure.persistence.repository;

import br.com.pixmanager.infrastructure.persistence.entity.ReceivedPixEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface ReceivedPixRepository extends JpaRepository<ReceivedPixEntity, UUID>, JpaSpecificationExecutor<ReceivedPixEntity> {

    Optional<ReceivedPixEntity> findByEndToEndId(String endToEndId);
}
