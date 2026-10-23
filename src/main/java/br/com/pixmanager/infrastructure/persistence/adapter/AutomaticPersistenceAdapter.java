package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.infrastructure.persistence.Pages;
import br.com.pixmanager.infrastructure.persistence.Timestamps;
import br.com.pixmanager.core.domain.entity.AutomaticAttempt;
import br.com.pixmanager.core.domain.entity.AutomaticAuthorization;
import br.com.pixmanager.core.domain.entity.AutomaticCharge;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.AutomaticPeriodicity;
import br.com.pixmanager.core.port.out.persistence.AutomaticPersistencePortOut;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.Set;
import java.util.UUID;

/** JDBC puro (claim com SKIP LOCKED). Documento do recebedor cifrado + índice cego + máscara; o documento do pagador é o do titular da conta. */
@Component
public class AutomaticPersistenceAdapter implements AutomaticPersistencePortOut {

    private static final String ACTOR = "pix-manager";
    private static final String ATTEMPT_COLUMNS = "id, charge_id, attempt_type, status, end_to_end_id, settlement_date, payment_order_id, last_error_code, version";

    private final JdbcClient jdbc;
    private final CryptoService crypto;

    public AutomaticPersistenceAdapter(JdbcClient jdbc, CryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    @Override
    @Transactional
    public void insertAuthorization(AutomaticAuthorization authorization) {
        Document receiver = Document.of(authorization.receiverDocument());
        try {
            jdbc.sql("""
                    INSERT INTO pix_automatic_authorization (id, id_rec, allow_retry, payer_account_id, payer_document_hash, receiver_document_enc,
                        receiver_document_hash, receiver_document_masked, receiver_name, contract, object_description, periodicity, start_date, end_date,
                        fixed_amount, receiver_min_amount, payer_max_amount, status, request_status, request_expires_at, created_by, updated_by)
                    VALUES (:id, :idRec, :retry, :payer, (SELECT h.document_hash FROM payment_account pa JOIN account_holder h ON h.id = pa.holder_id WHERE pa.id = :payer),
                        :docEnc, :docHash, :docMasked, :name, :contract, :object, :periodicity, :start, :end, :fixed, :min, :max, :status, :requestStatus,
                        :expires, :actor, :actor)""")
                    .param("id", authorization.id()).param("idRec", authorization.idRec()).param("retry", authorization.allowRetry())
                    .param("payer", authorization.payerAccountId())
                    .param("docEnc", crypto.encrypt(receiver.value())).param("docHash", crypto.blindIndex(receiver.value())).param("docMasked", receiver.masked())
                    .param("name", authorization.receiverName()).param("contract", authorization.contract())
                    .param("object", authorization.objectDescription()).param("periodicity", authorization.periodicity().name())
                    .param("start", authorization.startDate()).param("end", authorization.endDate())
                    .param("fixed", Money.valueOrNull(authorization.fixedAmount())).param("min", Money.valueOrNull(authorization.receiverMinAmount()))
                    .param("max", Money.valueOrNull(authorization.payerMaxAmount())).param("status", authorization.status())
                    .param("requestStatus", authorization.requestStatus())
                    .param("expires", Timestamp.from(authorization.requestExpiresAt())).param("actor", ACTOR).update();
        } catch (DuplicateKeyException exception) {
            throw new ConflictException(ErrorCodes.CONFLICT, "Já existe uma autorização com este idRec.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AutomaticAuthorization> findAuthorization(UUID id) {
        return jdbc.sql("SELECT * FROM pix_automatic_authorization WHERE id = :id").param("id", id).query(this::authorization).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AutomaticAuthorization> findAuthorizationByIdRec(String idRec) {
        return jdbc.sql("SELECT * FROM pix_automatic_authorization WHERE id_rec = :r").param("r", idRec).query(this::authorization).optional();
    }

    @Override
    @Transactional
    public AutomaticAuthorization saveAuthorization(AutomaticAuthorization authorization) {
        return jdbc.sql("""
                UPDATE pix_automatic_authorization SET status = :status, request_status = :rs, rejection_code = :rej, cancelled_by = :by, approved_at = :ap,
                    cancelled_at = :ca, payer_max_amount = :max, version = version + 1, updated_at = now(), updated_by = :actor
                WHERE id = :id AND version = :v
                RETURNING *""")
                .param("status", authorization.status()).param("rs", authorization.requestStatus()).param("rej", authorization.rejectionCode())
                .param("by", authorization.cancelledBy())
                .param("ap", Timestamps.from(authorization.approvedAt())).param("ca", Timestamps.from(authorization.cancelledAt()))
                .param("max", Money.valueOrNull(authorization.payerMaxAmount())).param("actor", ACTOR)
                .param("id", authorization.id()).param("v", authorization.version()).query(this::authorization).optional()
                .orElseThrow(() -> new PreconditionFailedException("A autorização foi alterada por outra requisição (versão desatualizada)."));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AutomaticAuthorization> listAuthorizations(Set<UUID> accounts, boolean allAccounts, String status, PageQuery page) {
        if (!allAccounts && accounts.isEmpty()) {
            return new PageResult<>(List.of(), page.page(), page.size(), 0);
        }
        String where = (allAccounts ? "TRUE" : "payer_account_id = ANY(:accounts)") + (status == null ? "" : " AND status = :status");
        var count = jdbc.sql("SELECT count(*) FROM pix_automatic_authorization WHERE " + where);
        var rows = jdbc.sql("SELECT * FROM pix_automatic_authorization WHERE " + where + " ORDER BY created_at DESC, id LIMIT :l OFFSET :o");
        if (!allAccounts) {
            UUID[] ids = accounts.toArray(new UUID[0]);
            count = count.param("accounts", ids);
            rows = rows.param("accounts", ids);
        }
        if (status != null) {
            count = count.param("status", status);
            rows = rows.param("status", status);
        }
        List<AutomaticAuthorization> content = rows.param("l", page.size()).param("o", (long) page.page() * page.size()).query(this::authorization).list();
        final var countQuery = count;
        return new PageResult<>(content, page.page(), page.size(), Pages.total(page, content, () -> countQuery.query(Long.class).single()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutomaticAuthorization> findExpiredRequests(Instant now, int limit) {
        return jdbc.sql("SELECT * FROM pix_automatic_authorization WHERE status = 'CRIADA' AND request_status IN ('RECEBIDA', 'ENVIADA') AND request_expires_at < :now ORDER BY request_expires_at LIMIT :l")
                .param("now", Timestamp.from(now)).param("l", limit).query(this::authorization).list();
    }

    @Override
    @Transactional
    public void insertCharge(AutomaticCharge automaticCharge, AutomaticAttempt first) {
        try {
            jdbc.sql("""
                    INSERT INTO pix_automatic_charge (id, authorization_id, txid, due_date, amount, status, receiver_ispb, receiver_branch, receiver_account, receiver_account_type)
                    VALUES (:id, :auth, :txid, :due, :amount, :status, :ispb, :branch, :account, :type)""")
                    .param("id", automaticCharge.id()).param("auth", automaticCharge.authorizationId()).param("txid", automaticCharge.txid())
                    .param("due", automaticCharge.dueDate()).param("amount", automaticCharge.amount().value())
                    .param("status", automaticCharge.status()).param("ispb", automaticCharge.receiverIspb())
                    .param("branch", automaticCharge.receiverBranch()).param("account", automaticCharge.receiverAccount())
                    .param("type", automaticCharge.receiverAccountType()).update();
        } catch (DuplicateKeyException exception) {
            throw new ConflictException(ErrorCodes.CONFLICT, "Já existe uma cobrança com este txid nesta autorização.");
        }
        insertAttempt(first);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AutomaticCharge> findCharge(UUID id) {
        return jdbc.sql("SELECT * FROM pix_automatic_charge WHERE id = :id").param("id", id).query(this::charge).optional();
    }

    @Override
    @Transactional
    public AutomaticCharge saveCharge(AutomaticCharge automaticCharge) {
        return jdbc.sql("UPDATE pix_automatic_charge SET status = :s, version = version + 1, updated_at = now() WHERE id = :id AND version = :v RETURNING *")
                .param("s", automaticCharge.status()).param("id", automaticCharge.id()).param("v", automaticCharge.version()).query(this::charge).optional()
                .orElseThrow(() -> new PreconditionFailedException("A cobrança foi alterada por outra requisição (versão desatualizada)."));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutomaticCharge> chargesOf(UUID authorizationId) {
        return jdbc.sql("SELECT * FROM pix_automatic_charge WHERE authorization_id = :a ORDER BY due_date, created_at").param("a", authorizationId).query(this::charge).list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutomaticAttempt> attemptsOf(UUID chargeId) {
        return jdbc.sql("SELECT " + ATTEMPT_COLUMNS + " FROM pix_automatic_attempt WHERE charge_id = :c ORDER BY created_at, settlement_date").param("c", chargeId).query(this::attempt).list();
    }

    @Override
    @Transactional
    public void insertAttempt(AutomaticAttempt automaticAttempt) {
        jdbc.sql("INSERT INTO pix_automatic_attempt (id, charge_id, attempt_type, status, end_to_end_id, settlement_date, payment_order_id) VALUES (:id, :c, :t, :s, :e2e, :d, :o)")
                .param("id", automaticAttempt.id()).param("c", automaticAttempt.chargeId()).param("t", automaticAttempt.type())
                .param("s", automaticAttempt.status()).param("e2e", automaticAttempt.endToEndId())
                .param("d", automaticAttempt.settlementDate()).param("o", automaticAttempt.paymentOrderId()).update();
    }

    @Override
    @Transactional
    public AutomaticAttempt saveAttempt(AutomaticAttempt automaticAttempt) {
        return jdbc.sql("UPDATE pix_automatic_attempt SET status = :s, end_to_end_id = :e2e, payment_order_id = :o, last_error_code = :err, version = version + 1, updated_at = now() "
                        + "WHERE id = :id AND version = :v RETURNING " + ATTEMPT_COLUMNS)
                .param("s", automaticAttempt.status()).param("e2e", automaticAttempt.endToEndId()).param("o", automaticAttempt.paymentOrderId())
                .param("err", automaticAttempt.lastErrorCode()).param("id", automaticAttempt.id()).param("v", automaticAttempt.version()).query(this::attempt).optional()
                .orElseThrow(() -> new PreconditionFailedException("A tentativa foi alterada por outra requisição (versão desatualizada)."));
    }

    @Override
    @Transactional
    public List<AutomaticAttempt> claimDue(LocalDate today, Instant notUpdatedSince, int limit) {
        return jdbc.sql("""
                UPDATE pix_automatic_attempt t SET status = 'SOLICITADA', version = t.version + 1, updated_at = now()
                 WHERE t.id IN (SELECT t2.id FROM pix_automatic_attempt t2 JOIN pix_automatic_charge c ON c.id = t2.charge_id
                                 WHERE t2.status = 'AGENDADA' AND t2.settlement_date <= :today AND c.status = 'ATIVA' AND t2.updated_at <= :since
                                 ORDER BY t2.settlement_date, t2.created_at LIMIT :limit FOR UPDATE OF t2 SKIP LOCKED)
                RETURNING t.id, t.charge_id, t.attempt_type, t.status, t.end_to_end_id, t.settlement_date, t.payment_order_id, t.last_error_code, t.version""")
                .param("today", today).param("since", Timestamp.from(notUpdatedSince)).param("limit", limit).query(this::attempt).list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutomaticAttempt> inProgressWithOrder(int limit) {
        return jdbc.sql("SELECT " + ATTEMPT_COLUMNS + " FROM pix_automatic_attempt WHERE status = 'SOLICITADA' AND payment_order_id IS NOT NULL ORDER BY updated_at LIMIT :l")
                .param("l", limit).query(this::attempt).list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutomaticAttempt> inProgressWithoutOrderSince(Instant olderThan, int limit) {
        return jdbc.sql("SELECT " + ATTEMPT_COLUMNS + " FROM pix_automatic_attempt WHERE status = 'SOLICITADA' AND payment_order_id IS NULL AND updated_at < :t ORDER BY updated_at LIMIT :l")
                .param("t", Timestamp.from(olderThan)).param("l", limit).query(this::attempt).list();
    }

    // ---------------------------------------------------------------- mapeamento

    private AutomaticAuthorization authorization(ResultSet resultSet, int row) throws SQLException {
        return new AutomaticAuthorization(resultSet.getObject("id", UUID.class), resultSet.getString("id_rec"), resultSet.getBoolean("allow_retry"),
                resultSet.getObject("payer_account_id", UUID.class),
                crypto.decrypt(resultSet.getString("receiver_document_enc")), resultSet.getString("receiver_name"), resultSet.getString("contract"), resultSet.getString("object_description"),
                AutomaticPeriodicity.valueOf(resultSet.getString("periodicity")), resultSet.getObject("start_date", LocalDate.class), resultSet.getObject("end_date", LocalDate.class),
                Money.ofNullable(resultSet.getBigDecimal("fixed_amount")), Money.ofNullable(resultSet.getBigDecimal("receiver_min_amount")), Money.ofNullable(resultSet.getBigDecimal("payer_max_amount")),
                resultSet.getString("status"), resultSet.getString("request_status"), resultSet.getString("rejection_code"), resultSet.getString("cancelled_by"),
                Timestamps.toInstant(resultSet.getTimestamp("approved_at")), Timestamps.toInstant(resultSet.getTimestamp("cancelled_at")), Timestamps.toInstant(resultSet.getTimestamp("request_expires_at")), resultSet.getLong("version"));
    }

    private AutomaticCharge charge(ResultSet resultSet, int row) throws SQLException {
        return new AutomaticCharge(resultSet.getObject("id", UUID.class), resultSet.getObject("authorization_id", UUID.class), resultSet.getString("txid"),
                resultSet.getObject("due_date", LocalDate.class),
                Money.of(resultSet.getBigDecimal("amount")), resultSet.getString("receiver_ispb"), resultSet.getString("receiver_branch"), resultSet.getString("receiver_account"),
                resultSet.getString("receiver_account_type"), resultSet.getString("status"), resultSet.getLong("version"));
    }

    private AutomaticAttempt attempt(ResultSet resultSet, int row) throws SQLException {
        return new AutomaticAttempt(resultSet.getObject("id", UUID.class), resultSet.getObject("charge_id", UUID.class), resultSet.getString("attempt_type"), resultSet.getString("status"),
                resultSet.getString("end_to_end_id"), resultSet.getObject("settlement_date", LocalDate.class), resultSet.getObject("payment_order_id", UUID.class),
                resultSet.getString("last_error_code"), resultSet.getLong("version"));
    }

    /** Cancela, em 2 UPDATEs (sem laço por cobrança), as tentativas AGENDADA e as cobranças ATIVA da autorização. */
    @Override
    @Transactional
    public void cancelLiveCharges(UUID authorizationId) {
        jdbc.sql("""
                UPDATE pix_automatic_attempt a SET status = 'CANCELADA', last_error_code = NULL, version = a.version + 1, updated_at = now()
                  FROM pix_automatic_charge c
                 WHERE a.charge_id = c.id AND c.authorization_id = :auth AND c.status = 'ATIVA' AND a.status = 'AGENDADA'""")
                .param("auth", authorizationId).update();
        jdbc.sql("UPDATE pix_automatic_charge SET status = 'CANCELADA', version = version + 1, updated_at = now() WHERE authorization_id = :auth AND status = 'ATIVA'")
                .param("auth", authorizationId).update();
    }
}
