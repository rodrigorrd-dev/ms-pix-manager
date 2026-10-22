package br.com.pixmanager.shared.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Idempotency-Key HTTP persistente (docs/05 §4.2). Diferenças deliberadas em relação ao Redis {@code SETNX} do
 * mesa-credito-full (que tinha 3 defeitos registrados em api-contract-review):
 * <ol>
 *   <li>replay devolve a resposta ORIGINAL (não 409);</li>
 *   <li>mesma chave + corpo diferente ⇒ {@link IdempotencyOutcome.Mismatch} (hash do corpo);</li>
 *   <li>falha técnica NÃO queima a chave: {@link #release} apaga o registro (lease {@code locked_until}).</li>
 * </ol>
 * {@code begin}/{@code release} usam transação própria ({@code REQUIRES_NEW}) para o registro ficar visível a requisições
 * concorrentes; {@code complete} roda na transação do negócio, então o replay só existe se o commit ocorreu.
 */
@Component
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate newTx;

    public IdempotencyService(JdbcClient jdbc, Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public IdempotencyOutcome begin(String clientId, String operation, String key, String requestBody,
                                    Duration lease, Duration ttl) {
        String hash = sha256(requestBody == null ? "" : requestBody);
        Instant now = clock.instant();
        return newTx.execute(status -> {
            UUID id = UUID.randomUUID();
            int inserted = jdbc.sql("""
                    INSERT INTO idempotency_record (id, client_id, operation, idempotency_key, request_hash, status,
                                                    locked_until, created_at, expires_at)
                    VALUES (:id, :client, :operation, :key, :hash, 'IN_PROGRESS', :lockedUntil, :now, :expires)
                    ON CONFLICT (client_id, operation, idempotency_key) DO NOTHING
                    """)
                    .param("id", id).param("client", clientId).param("operation", operation).param("key", key)
                    .param("hash", hash).param("lockedUntil", Timestamp.from(now.plus(lease)))
                    .param("now", Timestamp.from(now)).param("expires", Timestamp.from(now.plus(ttl)))
                    .update();
            if (inserted == 1) {
                return new IdempotencyOutcome.Proceed(id);
            }
            var existing = jdbc.sql("""
                    SELECT id, request_hash, status, locked_until, response_status, response_body::text AS body
                      FROM idempotency_record
                     WHERE client_id = :client AND operation = :operation AND idempotency_key = :key
                       FOR UPDATE
                    """)
                    .param("client", clientId).param("operation", operation).param("key", key)
                    .query((resultSet, rowNumber) -> new Existing(resultSet.getObject("id", UUID.class), resultSet.getString("request_hash"),
                            resultSet.getString("status"), resultSet.getTimestamp("locked_until"), resultSet.getInt("response_status"),
                            resultSet.getString("body")))
                    .single();
            if (!existing.hash().equals(hash)) {
                return new IdempotencyOutcome.Mismatch();
            }
            if ("COMPLETED".equals(existing.status())) {
                return new IdempotencyOutcome.Replay(existing.responseStatus(), existing.body());
            }
            if (existing.lockedUntil() != null && existing.lockedUntil().toInstant().isAfter(now)) {
                return new IdempotencyOutcome.InProgress();
            }
            jdbc.sql("UPDATE idempotency_record SET locked_until = :lockedUntil WHERE id = :id")
                    .param("lockedUntil", Timestamp.from(now.plus(lease))).param("id", existing.id()).update();
            return new IdempotencyOutcome.Proceed(existing.id());
        });
    }

    /** Chamar DENTRO da transação do negócio: o replay só passa a existir se o commit ocorrer. */
    public void complete(UUID recordId, int responseStatus, String responseBodyJson) {
        jdbc.sql("""
                UPDATE idempotency_record
                   SET status = 'COMPLETED', response_status = :status, response_body = CAST(:body AS jsonb), locked_until = NULL
                 WHERE id = :id
                """)
                .param("status", (short) responseStatus).param("body", responseBodyJson).param("id", recordId).update();
    }

    /** Falha técnica/negócio antes do commit: libera a chave (não "queima"). */
    public void release(UUID recordId) {
        newTx.executeWithoutResult(status ->
                jdbc.sql("DELETE FROM idempotency_record WHERE id = :id AND status = 'IN_PROGRESS'")
                        .param("id", recordId).update());
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Existing(UUID id, String hash, String status, Timestamp lockedUntil, int responseStatus, String body) {}
}
