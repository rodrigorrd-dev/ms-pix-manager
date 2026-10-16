package br.com.pixmanager.infrastructure.persistence.adapter;

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
import java.util.ArrayList;
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
    public void insert(MedCase c) {
        jdbc.sql("""
                INSERT INTO med_case (id, dict_funds_recovery_id, end_to_end_id, received_id, payment_order_id, account_id, case_type, role, status,
                    layer, description, reported_at, analysis_deadline_at, tracked_amount, blocked_amount, created_by, updated_by)
                VALUES (:id, :dict, :e2e, :received, :order, :account, :type, :role, :status, :layer, :desc, :reported, :deadline, :tracked, :blocked, :actor, :actor)""")
                .param("id", c.id()).param("dict", c.dictFundsRecoveryId()).param("e2e", c.endToEndId()).param("received", c.receivedId())
                .param("order", c.paymentOrderId()).param("account", c.accountId()).param("type", c.caseType().name()).param("role", c.role().name())
                .param("status", c.status().name()).param("layer", c.layer()).param("desc", c.description()).param("reported", Timestamp.from(c.reportedAt()))
                .param("deadline", Timestamp.from(c.analysisDeadlineAt())).param("tracked", c.trackedAmount().value())
                .param("blocked", c.blockedAmount().value()).param("actor", ACTOR).update();
    }

    @Override
    @Transactional
    public MedCase save(MedCase c) {
        int rows = jdbc.sql("UPDATE med_case SET status = :status, blocked_amount = :blocked, refund_id = :refund, version = version + 1, updated_at = now(), updated_by = :actor WHERE id = :id AND version = :v")
                .param("status", c.status().name()).param("blocked", c.blockedAmount().value()).param("refund", c.refundId()).param("actor", ACTOR)
                .param("id", c.id()).param("v", c.version()).update();
        if (rows == 0) {
            throw new PreconditionFailedException("O caso MED foi alterado por outra requisição (versão desatualizada).");
        }
        return findById(c.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MedCase> findById(UUID id) {
        return jdbc.sql("SELECT * FROM med_case WHERE id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsLive(String e2e, MedRole role, int layer) {
        return jdbc.sql("SELECT count(*) FROM med_case WHERE end_to_end_id = :e AND role = :r AND layer = :l AND status IN ('OPEN', 'ACCEPTED', 'REFUNDING')")
                .param("e", e2e).param("r", role.name()).param("l", layer).query(Long.class).single() > 0;
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
        long total = count.query(Long.class).single();
        return new PageResult<>(rows.param("l", page.size()).param("o", (long) page.page() * page.size()).query(this::map).list(), page.page(), page.size(), total);
    }

    @Override
    @Transactional
    public void appendHistory(UUID caseId, MedStatus from, MedStatus to, String actor, String note, Instant at) {
        jdbc.sql("INSERT INTO med_case_history (id, case_id, from_status, to_status, actor, note, occurred_at) VALUES (:id, :c, :f, :t, :a, :n, :at)")
                .param("id", UUID.randomUUID()).param("c", caseId).param("f", from == null ? null : from.name()).param("t", to.name())
                .param("a", actor).param("n", note == null ? null : note.substring(0, Math.min(note.length(), 500))).param("at", Timestamp.from(at)).update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID caseId) {
        return jdbc.sql("SELECT from_status, to_status, actor, note, occurred_at FROM med_case_history WHERE case_id = :c ORDER BY occurred_at, id")
                .param("c", caseId).query((rs, i) -> new HistoryEntry(rs.getString("from_status") == null ? null : MedStatus.valueOf(rs.getString("from_status")),
                        MedStatus.valueOf(rs.getString("to_status")), rs.getString("actor"), rs.getString("note"), rs.getTimestamp("occurred_at").toInstant())).list();
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
    public void insertContestation(MedContestation c) {
        try {
            jdbc.sql("INSERT INTO med_contestation (id, case_id, refund_id, status, reason, refunded_at, deadline_at, created_by) VALUES (:id, :c, :r, 'OPEN', :reason, :ra, :d, :actor)")
                    .param("id", c.id()).param("c", c.caseId()).param("r", c.refundId()).param("reason", c.reason()).param("ra", Timestamp.from(c.refundedAt()))
                    .param("d", Timestamp.from(c.deadlineAt())).param("actor", ACTOR).update();
        } catch (DuplicateKeyException e) {
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
    public MedContestation saveContestation(MedContestation c) {
        int rows = jdbc.sql("UPDATE med_contestation SET status = :s, decided_at = :d, version = version + 1, updated_at = now() WHERE id = :id AND version = :v")
                .param("s", c.status()).param("d", c.decidedAt() == null ? null : Timestamp.from(c.decidedAt())).param("id", c.id()).param("v", c.version()).update();
        if (rows == 0) {
            throw new PreconditionFailedException("A contestação foi alterada por outra requisição (versão desatualizada).");
        }
        return findContestation(c.id()).orElseThrow();
    }

    private MedCase map(ResultSet rs, int row) throws SQLException {
        BigDecimal tracked = rs.getBigDecimal("tracked_amount");
        return new MedCase(rs.getObject("id", UUID.class), rs.getString("dict_funds_recovery_id"), rs.getString("end_to_end_id"),
                rs.getObject("received_id", UUID.class), rs.getObject("payment_order_id", UUID.class), rs.getObject("account_id", UUID.class),
                MedCaseType.valueOf(rs.getString("case_type")), MedRole.valueOf(rs.getString("role")), MedStatus.valueOf(rs.getString("status")),
                rs.getInt("layer"), rs.getString("description"), rs.getTimestamp("reported_at").toInstant(), rs.getTimestamp("analysis_deadline_at").toInstant(),
                tracked == null ? Money.ZERO : Money.of(tracked), Money.of(rs.getBigDecimal("blocked_amount")), rs.getObject("refund_id", UUID.class), rs.getLong("version"));
    }

    private MedContestation contestation(ResultSet rs, int row) throws SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new MedContestation(rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class), rs.getObject("refund_id", UUID.class), rs.getString("status"),
                rs.getString("reason"), rs.getTimestamp("refunded_at").toInstant(), rs.getTimestamp("deadline_at").toInstant(),
                decided == null ? null : decided.toInstant(), rs.getLong("version"));
    }
}
