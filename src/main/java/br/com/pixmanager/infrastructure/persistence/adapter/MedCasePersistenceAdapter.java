package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.shared.util.Text;
import br.com.pixmanager.infrastructure.persistence.Pages;
import br.com.pixmanager.infrastructure.persistence.Timestamps;
import br.com.pixmanager.core.domain.entity.MedCase;
import br.com.pixmanager.core.domain.entity.MedContestation;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.MedCaseType;
import br.com.pixmanager.core.domain.enums.MedRole;
import br.com.pixmanager.core.domain.enums.MedStatus;
import br.com.pixmanager.core.port.out.persistence.MedCasePersistencePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** JDBC puro: tabelas {@code med_*}. A trilha {@code med_case_history} é append-only (trigger no banco). */
@Component
public class MedCasePersistenceAdapter implements MedCasePersistencePortOut {

    private static final String ACTOR = "pix-manager";

    private final JdbcClient jdbc;

    public MedCasePersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void insert(MedCase medCase) {
        jdbc.sql("""
                INSERT INTO med_case (id, dict_funds_recovery_id, end_to_end_id, received_id, payment_order_id, account_id, case_type, role, status,
                    layer, description, reported_at, analysis_deadline_at, tracked_amount, blocked_amount, created_by, updated_by)
                VALUES (:id, :dict, :e2e, :received, :order, :account, :type, :role, :status, :layer, :desc, :reported, :deadline, :tracked, :blocked, :actor, :actor)""")
                .param("id", medCase.id()).param("dict", medCase.dictFundsRecoveryId()).param("e2e", medCase.endToEndId()).param("received", medCase.receivedId())
                .param("order", medCase.paymentOrderId()).param("account", medCase.accountId()).param("type", medCase.caseType().name())
                .param("role", medCase.role().name())
                .param("status", medCase.status().name()).param("layer", medCase.layer()).param("desc", medCase.description())
                .param("reported", Timestamp.from(medCase.reportedAt()))
                .param("deadline", Timestamp.from(medCase.analysisDeadlineAt())).param("tracked", medCase.trackedAmount().value())
                .param("blocked", medCase.blockedAmount().value()).param("actor", ACTOR).update();
    }

    @Override
    @Transactional
    public MedCase save(MedCase medCase) {
        int rows = jdbc.sql("UPDATE med_case SET status = :status, blocked_amount = :blocked, refund_id = :refund, version = version + 1, updated_at = now(), updated_by = :actor WHERE id = :id AND version = :v")
                .param("status", medCase.status().name()).param("blocked", medCase.blockedAmount().value()).param("refund", medCase.refundId()).param("actor", ACTOR)
                .param("id", medCase.id()).param("v", medCase.version()).update();
        if (rows == 0) {
            throw new PreconditionFailedException("O caso MED foi alterado por outra requisição (versão desatualizada).");
        }
        return findById(medCase.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MedCase> findById(UUID id) {
        return jdbc.sql("SELECT * FROM med_case WHERE id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsLive(String endToEndId, MedRole role, int layer) {
        return jdbc.sql("SELECT count(*) FROM med_case WHERE end_to_end_id = :e AND role = :r AND layer = :l AND status IN ('OPEN', 'ACCEPTED', 'REFUNDING')")
                .param("e", endToEndId).param("r", role.name()).param("l", layer).query(Long.class).single() > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<MedCase> list(Set<UUID> accounts, boolean allAccounts, MedStatus status, PageQuery page) {
        if (!allAccounts && accounts.isEmpty()) {
            return new PageResult<>(List.of(), page.page(), page.size(), 0);
        }
        String where = (allAccounts ? "TRUE" : "account_id = ANY(:accounts)") + (status == null ? "" : " AND status = :status");
        var count = jdbc.sql("SELECT count(*) FROM med_case WHERE " + where);
        var rows = jdbc.sql("SELECT * FROM med_case WHERE " + where + " ORDER BY reported_at DESC, id LIMIT :l OFFSET :o");
        if (!allAccounts) {
            UUID[] ids = accounts.toArray(new UUID[0]);
            count = count.param("accounts", ids);
            rows = rows.param("accounts", ids);
        }
        if (status != null) {
            count = count.param("status", status.name());
            rows = rows.param("status", status.name());
        }
        List<MedCase> content = rows.param("l", page.size()).param("o", (long) page.page() * page.size()).query(this::map).list();
        final var countQuery = count;
        return new PageResult<>(content, page.page(), page.size(), Pages.total(page, content, () -> countQuery.query(Long.class).single()));
    }

    @Override
    @Transactional
    public void appendHistory(UUID caseId, MedStatus from, MedStatus to, String actor, String note, Instant at) {
        jdbc.sql("INSERT INTO med_case_history (id, case_id, from_status, to_status, actor, note, occurred_at) VALUES (:id, :c, :f, :t, :a, :n, :at)")
                .param("id", UUID.randomUUID()).param("c", caseId).param("f", from == null ? null : from.name()).param("t", to.name())
                .param("a", actor).param("n", Text.truncate(note, 500)).param("at", Timestamp.from(at)).update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID caseId) {
        return jdbc.sql("SELECT from_status, to_status, actor, note, occurred_at FROM med_case_history WHERE case_id = :c ORDER BY occurred_at, id")
                .param("c", caseId).query((resultSet, rowNumber) -> new HistoryEntry(resultSet.getString("from_status") == null ? null : MedStatus.valueOf(resultSet.getString("from_status")), MedStatus.valueOf(resultSet.getString("to_status")), resultSet.getString("actor"), resultSet.getString("note"), resultSet.getTimestamp("occurred_at").toInstant())).list();
    }

    @Override
    @Transactional
    public void insertBlock(UUID caseId, UUID accountId, BigDecimal amount, UUID reservationId) {
        jdbc.sql("INSERT INTO med_block (id, case_id, account_id, amount, ledger_reservation_id, status) VALUES (:id, :c, :a, :amt, :r, 'ACTIVE')")
                .param("id", UUID.randomUUID()).param("c", caseId).param("a", accountId).param("amt", amount).param("r", reservationId).update();
    }

    @Override
    @Transactional
    public void releaseBlock(UUID caseId) {
        jdbc.sql("UPDATE med_block SET status = 'RELEASED', updated_at = now(), version = version + 1 WHERE case_id = :c AND status = 'ACTIVE'").param("c", caseId).update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MedCase> findRefunding(int limit) {
        return jdbc.sql("SELECT * FROM med_case WHERE status = 'REFUNDING' ORDER BY updated_at LIMIT :l").param("l", limit).query(this::map).list();
    }

    @Override
    @Transactional
    public void insertContestation(MedContestation medContestation) {
        try {
            jdbc.sql("INSERT INTO med_contestation (id, case_id, refund_id, status, reason, refunded_at, deadline_at, created_by) VALUES (:id, :c, :r, 'OPEN', :reason, :ra, :d, :actor)")
                    .param("id", medContestation.id()).param("c", medContestation.caseId()).param("r", medContestation.refundId())
                    .param("reason", medContestation.reason()).param("ra", Timestamp.from(medContestation.refundedAt()))
                    .param("d", Timestamp.from(medContestation.deadlineAt())).param("actor", ACTOR).update();
        } catch (DuplicateKeyException exception) {
            throw new ConflictException(ErrorCodes.CONFLICT, "Já existe uma contestação aberta para esta devolução.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MedContestation> findContestation(UUID id) {
        return jdbc.sql("SELECT * FROM med_contestation WHERE id = :id").param("id", id).query(this::contestation).optional();
    }

    @Override
    @Transactional
    public MedContestation saveContestation(MedContestation medContestation) {
        int rows = jdbc.sql("UPDATE med_contestation SET status = :s, decided_at = :d, version = version + 1, updated_at = now() WHERE id = :id AND version = :v")
                .param("s", medContestation.status()).param("d", Timestamps.from(medContestation.decidedAt())).param("id", medContestation.id())
                .param("v", medContestation.version()).update();
        if (rows == 0) {
            throw new PreconditionFailedException("A contestação foi alterada por outra requisição (versão desatualizada).");
        }
        return findContestation(medContestation.id()).orElseThrow();
    }

    private MedCase map(ResultSet resultSet, int row) throws SQLException {
        BigDecimal tracked = resultSet.getBigDecimal("tracked_amount");
        return new MedCase(resultSet.getObject("id", UUID.class), resultSet.getString("dict_funds_recovery_id"), resultSet.getString("end_to_end_id"),
                resultSet.getObject("received_id", UUID.class), resultSet.getObject("payment_order_id", UUID.class), resultSet.getObject("account_id", UUID.class),
                MedCaseType.valueOf(resultSet.getString("case_type")), MedRole.valueOf(resultSet.getString("role")), MedStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("layer"), resultSet.getString("description"), resultSet.getTimestamp("reported_at").toInstant(), resultSet.getTimestamp("analysis_deadline_at").toInstant(),
                tracked == null ? Money.ZERO : Money.of(tracked), Money.of(resultSet.getBigDecimal("blocked_amount")), resultSet.getObject("refund_id", UUID.class), resultSet.getLong("version"));
    }

    private MedContestation contestation(ResultSet resultSet, int row) throws SQLException {
        Timestamp decided = resultSet.getTimestamp("decided_at");
        return new MedContestation(resultSet.getObject("id", UUID.class), resultSet.getObject("case_id", UUID.class), resultSet.getObject("refund_id", UUID.class), resultSet.getString("status"),
                resultSet.getString("reason"), resultSet.getTimestamp("refunded_at").toInstant(), resultSet.getTimestamp("deadline_at").toInstant(),
                Timestamps.toInstant(decided), resultSet.getLong("version"));
    }
}
