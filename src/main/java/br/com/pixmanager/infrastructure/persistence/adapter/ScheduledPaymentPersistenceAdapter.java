package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.ScheduledOccurrence;
import br.com.pixmanager.core.domain.entity.ScheduledPayment;
import br.com.pixmanager.core.domain.enums.OccurrenceStatus;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.enums.Recurrence;
import br.com.pixmanager.core.domain.enums.ScheduleStatus;
import br.com.pixmanager.core.port.out.persistence.ScheduledPaymentPersistencePortOut;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC puro: o worker usa {@code FOR UPDATE SKIP LOCKED} e UPDATE ... RETURNING, que o JPA não expressa. PII (chave e documento) cifrada. */
@Component
public class ScheduledPaymentPersistenceAdapter implements ScheduledPaymentPersistencePortOut {

    private static final String ACTOR = "pix-manager";
    private static final String OCCURRENCE_COLUMNS = "id, scheduled_payment_id, sequence_no, due_date, status, attempt_count, next_attempt_at,"
            + " customer_notified_at, payment_order_id, last_error_code, version";

    private final JdbcClient jdbc;
    private final CryptoService crypto;

    public ScheduledPaymentPersistenceAdapter(JdbcClient jdbc, CryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Override
    @Transactional
    public void insert(ScheduledPayment p, List<ScheduledOccurrence> occurrences) {
        Document doc = p.document() == null || p.document().isBlank() ? null : Document.of(p.document());
        jdbc.sql("""
                INSERT INTO scheduled_payment (id, payer_account_id, recurrence, payment_method, destination_key_type, destination_key_hash,
                    destination_key_enc, dest_document_type, dest_document_enc, dest_document_hash, dest_document_masked, dest_name, dest_ispb,
                    dest_branch, dest_account, dest_account_type, amount, payer_message, first_date, end_date, occurrences_total, status,
                    device_registered, created_by, updated_by)
                VALUES (:id, :payer, :recurrence, :method, :keyType, :keyHash, :keyEnc, :docType, :docEnc, :docHash, :docMasked, :name, :ispb,
                    :branch, :account, :accountType, :amount, :message, :first, :end, :total, :status, :device, :actor, :actor)""")
                .param("id", p.id()).param("payer", p.payerAccountId()).param("recurrence", p.recurrence().name())
                .param("method", p.method().name()).param("keyType", p.pixKeyType() == null ? null : p.pixKeyType().name())
                .param("keyHash", p.pixKey() == null ? null : crypto.blindIndex(p.pixKey()))
                .param("keyEnc", p.pixKey() == null ? null : crypto.encrypt(p.pixKey()))
                .param("docType", doc == null ? null : doc.type()).param("docEnc", doc == null ? null : crypto.encrypt(doc.value()))
                .param("docHash", doc == null ? null : crypto.blindIndex(doc.value())).param("docMasked", doc == null ? null : doc.masked())
                .param("name", p.name()).param("ispb", p.ispb()).param("branch", p.branch()).param("account", p.account())
                .param("accountType", p.accountType()).param("amount", p.amount().value()).param("message", p.payerMessage())
                .param("first", p.firstDate()).param("end", p.endDate()).param("total", p.occurrencesTotal())
                .param("status", p.status().name()).param("device", p.deviceRegistered()).param("actor", ACTOR).update();
        for (ScheduledOccurrence o : occurrences) {
            jdbc.sql("INSERT INTO scheduled_payment_occurrence (id, scheduled_payment_id, sequence_no, due_date, status) VALUES (:id, :sp, :seq, :due, :status)")
                    .param("id", o.id()).param("sp", o.scheduledPaymentId()).param("seq", o.sequenceNo()).param("due", o.dueDate())
                    .param("status", o.status().name()).update();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ScheduledPayment> findById(UUID id) {
        return jdbc.sql("SELECT * FROM scheduled_payment WHERE id = :id").param("id", id).query(this::payment).optional();
    }

    @Override
    @Transactional
    public ScheduledPayment save(ScheduledPayment p) {
        int rows = jdbc.sql("UPDATE scheduled_payment SET status = :status, version = version + 1, updated_at = now(), updated_by = :actor WHERE id = :id AND version = :v")
                .param("status", p.status().name()).param("actor", ACTOR).param("id", p.id()).param("v", p.version()).update();
        if (rows == 0) {
            throw new PreconditionFailedException("O agendamento foi alterado por outra requisição (versão desatualizada).");
        }
        return findById(p.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ScheduledPayment> listByPayer(UUID payerAccountId, PageQuery page) {
        long total = jdbc.sql("SELECT count(*) FROM scheduled_payment WHERE payer_account_id = :p").param("p", payerAccountId).query(Long.class).single();
        List<ScheduledPayment> content = jdbc.sql("SELECT * FROM scheduled_payment WHERE payer_account_id = :p ORDER BY created_at DESC, id LIMIT :l OFFSET :o")
                .param("p", payerAccountId).param("l", page.size()).param("o", (long) page.page() * page.size()).query(this::payment).list();
        return new PageResult<>(content, page.page(), page.size(), total);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScheduledOccurrence> occurrencesOf(UUID id) {
        return jdbc.sql("SELECT " + OCCURRENCE_COLUMNS + " FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id ORDER BY sequence_no")
                .param("id", id).query(this::occurrence).list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ScheduledOccurrence> findOccurrence(UUID id) {
        return jdbc.sql("SELECT " + OCCURRENCE_COLUMNS + " FROM scheduled_payment_occurrence WHERE id = :id").param("id", id).query(this::occurrence).optional();
    }

    @Override
    @Transactional
    public ScheduledOccurrence saveOccurrence(ScheduledOccurrence o) {
        int rows = jdbc.sql("""
                UPDATE scheduled_payment_occurrence SET status = :status, attempt_count = :attempts, next_attempt_at = :next,
                    customer_notified_at = :notified, payment_order_id = :order, last_error_code = :error, version = version + 1, updated_at = now()
                WHERE id = :id AND version = :v""")
                .param("status", o.status().name()).param("attempts", o.attemptCount()).param("next", ts(o.nextAttemptAt()))
                .param("notified", ts(o.customerNotifiedAt())).param("order", o.paymentOrderId()).param("error", o.lastErrorCode())
                .param("id", o.id()).param("v", o.version()).update();
        if (rows == 0) {
            throw new PreconditionFailedException("A ocorrência foi alterada por outra requisição (versão desatualizada).");
        }
        if (o.paymentOrderId() != null) {                                                    // rastreabilidade ordem → ocorrência
            jdbc.sql("UPDATE payment_order SET scheduled_occurrence_id = :o WHERE id = :order AND scheduled_occurrence_id IS DISTINCT FROM :o")
                    .param("o", o.id()).param("order", o.paymentOrderId()).update();
        }
        return findOccurrence(o.id()).orElseThrow();
    }

    @Override
    @Transactional
    public List<ScheduledOccurrence> claimDue(LocalDate today, Instant now, int limit) {
        return jdbc.sql("""
                UPDATE scheduled_payment_occurrence o
                   SET status = 'PROCESSING', attempt_count = o.attempt_count + 1, next_attempt_at = NULL, payment_order_id = NULL,
                       version = o.version + 1, updated_at = now()
                 WHERE o.id IN (SELECT o2.id FROM scheduled_payment_occurrence o2
                                  JOIN scheduled_payment sp ON sp.id = o2.scheduled_payment_id
                                 WHERE o2.status IN ('SCHEDULED', 'RETRY_PENDING') AND o2.due_date <= :today
                                   AND (o2.next_attempt_at IS NULL OR o2.next_attempt_at <= :now) AND sp.status = 'ACTIVE'
                                 ORDER BY o2.due_date, o2.sequence_no LIMIT :limit
                                 FOR UPDATE OF o2 SKIP LOCKED)
                RETURNING o.id, o.scheduled_payment_id, o.sequence_no, o.due_date, o.status, o.attempt_count, o.next_attempt_at,
                          o.customer_notified_at, o.payment_order_id, o.last_error_code, o.version""")
                .param("today", today).param("now", ts(now)).param("limit", limit).query(this::occurrence).list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScheduledOccurrence> processingWithOrder(int limit) {
        return jdbc.sql("SELECT " + OCCURRENCE_COLUMNS + " FROM scheduled_payment_occurrence WHERE status = 'PROCESSING' AND payment_order_id IS NOT NULL ORDER BY updated_at LIMIT :l")
                .param("l", limit).query(this::occurrence).list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScheduledOccurrence> processingWithoutOrderSince(Instant olderThan, int limit) {
        return jdbc.sql("SELECT " + OCCURRENCE_COLUMNS + " FROM scheduled_payment_occurrence WHERE status = 'PROCESSING' AND payment_order_id IS NULL AND updated_at < :t ORDER BY updated_at LIMIT :l")
                .param("t", ts(olderThan)).param("l", limit).query(this::occurrence).list();
    }

    @Override
    @Transactional(readOnly = true)
    public long countOpen(UUID id) {
        return jdbc.sql("SELECT count(*) FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id AND status IN ('SCHEDULED', 'PROCESSING', 'RETRY_PENDING')")
                .param("id", id).query(Long.class).single();
    }

    // ---------------------------------------------------------------- mapeamento

    private ScheduledPayment payment(ResultSet rs, int row) throws SQLException {
        String keyEnc = rs.getString("destination_key_enc");
        String docEnc = rs.getString("dest_document_enc");
        String keyType = rs.getString("destination_key_type");
        return new ScheduledPayment(rs.getObject("id", UUID.class), rs.getObject("payer_account_id", UUID.class),
                Recurrence.valueOf(rs.getString("recurrence")), PaymentMethod.valueOf(rs.getString("payment_method")),
                keyType == null ? null : PixKeyType.valueOf(keyType), keyEnc == null ? null : crypto.decrypt(keyEnc), rs.getString("dest_ispb"),
                rs.getString("dest_branch"), rs.getString("dest_account"), rs.getString("dest_account_type"), rs.getString("dest_name"),
                docEnc == null ? null : crypto.decrypt(docEnc), Money.of(rs.getBigDecimal("amount")), rs.getString("payer_message"),
                rs.getObject("first_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                (Integer) rs.getObject("occurrences_total"), ScheduleStatus.valueOf(rs.getString("status")), rs.getBoolean("device_registered"),
                rs.getLong("version"));
    }

    private ScheduledOccurrence occurrence(ResultSet rs, int row) throws SQLException {
        return new ScheduledOccurrence(rs.getObject("id", UUID.class), rs.getObject("scheduled_payment_id", UUID.class), rs.getInt("sequence_no"),
                rs.getObject("due_date", LocalDate.class), OccurrenceStatus.valueOf(rs.getString("status")), rs.getInt("attempt_count"),
                instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("customer_notified_at")),
                rs.getObject("payment_order_id", UUID.class), rs.getString("last_error_code"), rs.getLong("version"));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
