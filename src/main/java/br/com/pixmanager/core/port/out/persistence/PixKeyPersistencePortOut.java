package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PixKeyPersistencePortOut {

    PixKey save(PixKey key);

    Optional<PixKey> findById(UUID id);

    /** Chave "viva" (não DELETED/REJECTED) com esse valor, em todo o sistema (BR-DOC-04). */
    Optional<PixKey> findLiveByValue(PixKeyValue value);

    long countLiveByAccount(UUID accountId);

    PageResult<PixKey> list(UUID accountId, Optional<PixKeyStatus> status, PageQuery page);

    /** Para o job: PENDING_DICT mais antigas que {@code olderThan}. */
    List<PixKey> findPendingDict(Instant olderThan, int limit);
}
