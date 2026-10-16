package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.MedCase;
import br.com.pixmanager.core.domain.entity.MedContestation;
import br.com.pixmanager.core.domain.enums.MedRole;
import br.com.pixmanager.core.domain.enums.MedStatus;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MedCasePersistencePortOut {

    void insert(MedCase medCase);

    /** Versão desatualizada ⇒ {@code PreconditionFailedException}. */
    MedCase save(MedCase medCase);

    Optional<MedCase> findById(UUID id);

    /** Caso ainda não terminado para o mesmo Pix, papel e camada (evita duplicar a notificação). */
    boolean existsLive(String endToEndId, MedRole role, int layer);

    PageResult<MedCase> list(Set<UUID> accounts, boolean allAccounts, MedStatus status, PageQuery page);

    /** Trilha append-only (BR-MED-08). */
    void appendHistory(UUID caseId, MedStatus from, MedStatus to, String actor, String note, Instant at);

    List<HistoryEntry> history(UUID caseId);

    void insertBlock(UUID caseId, UUID accountId, java.math.BigDecimal amount, UUID reservationId);

    void releaseBlock(UUID caseId);

    List<MedCase> findRefunding(int limit);

    void insertContestation(MedContestation contestation);

    Optional<MedContestation> findContestation(UUID id);

    MedContestation saveContestation(MedContestation contestation);

    record HistoryEntry(MedStatus from, MedStatus to, String actor, String note, Instant occurredAt) {}
}
