package br.com.pixmanager.infrastructure.persistence.repository;

import br.com.pixmanager.infrastructure.persistence.entity.ChargeEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChargeRepository extends JpaRepository<ChargeEntity, UUID>, JpaSpecificationExecutor<ChargeEntity> {

    Optional<ChargeEntity> findByReceiverDocumentHashAndTxid(String receiverDocumentHash, String txid);

    List<ChargeEntity> findByTxid(String txid);
}
