package br.com.pixmanager.core.port.out.dict;

import br.com.pixmanager.core.domain.enums.DeletionReason;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.shared.model.document.Document;

import java.time.Instant;
import java.util.Optional;

/**
 * DICT SOMENTE via {@code dict-gateway} (decisão A1): limite de consultas, cache e retentativas ficam lá; o ms não faz
 * mTLS/XMLDSig do DICT. 4xx definitivo ⇒ {@code BusinessException}; 429/5xx/timeout ⇒ {@code IntegrationException}.
 */
public interface DictGatewayPortOut {

    DictRegistration register(PixKey key);

    void delete(PixKey key, DeletionReason reason);

    /** {@code requester} = documento do pagador (o DICT exige para auditoria/limite de consulta). */
    Optional<DictEntry> lookup(PixKeyValue value, Document requester);

    record DictRegistration(String dictCid, Instant registeredAt) {}

    record DictEntry(String ownerName, Document ownerDocument, String ispb, String branch, String accountNumber,
                     String accountType, Instant ownershipDate) {}
}
