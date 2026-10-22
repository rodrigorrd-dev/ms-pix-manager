package br.com.pixmanager.infrastructure.persistence.adapter;

import br.com.pixmanager.core.domain.entity.WebhookDelivery;
import br.com.pixmanager.core.domain.enums.DeliveryStatus;
import br.com.pixmanager.core.port.out.persistence.WebhookDeliveryPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@code webhook_delivery} via JDBC. Atualização com controle otimista de versão (duas instâncias nunca sobrescrevem uma à outra). */
@Component
public class WebhookDeliveryPersistenceAdapter implements WebhookDeliveryPortOut {

    private static final String COLUMNS = "d.id, d.subscription_id, d.source_event_id, d.event_type, d.status, d.attempts, d.next_attempt_at, "
            + "d.last_http_status, d.last_error, d.delivered_at, d.version";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public WebhookDeliveryPersistenceAdapter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void enqueue(WebhookDelivery delivery, Map<String, Object> payload) {
        jdbc.sql("""
                INSERT INTO webhook_delivery (id, subscription_id, source_event_id, event_type, payload, status, attempts, next_attempt_at)
                VALUES (:id, :sub, :event, :type, CAST(:payload AS jsonb), 'PENDING', 0, :next)
                ON CONFLICT (subscription_id, source_event_id) DO NOTHING
                """)
                .param("id", delivery.id()).param("sub", delivery.subscriptionId()).param("event", delivery.sourceEventId()).param("type", delivery.eventType())
                .param("payload", toJson(payload)).param("next", Timestamp.from(delivery.nextAttemptAt())).update();
    }

    @Override
    public List<DueDelivery> findDue(Instant now, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + ", s.url, d.payload::text AS payload_json FROM webhook_delivery d "
                        + "JOIN webhook_subscription s ON s.id = d.subscription_id "
                        + "WHERE d.status IN ('PENDING','RETRYING') AND d.next_attempt_at <= :now AND s.status = 'ACTIVE' "
                        + "ORDER BY d.next_attempt_at, d.id LIMIT :n")
                .param("now", Timestamp.from(now)).param("n", limit)
                .query((resultSet, rowNumber) -> new DueDelivery(map(resultSet), resultSet.getString("url"), resultSet.getString("payload_json"))).list();
    }

    @Override
    public Optional<WebhookDelivery> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_delivery d WHERE d.id = :id").param("id", id).query((resultSet, rowNumber) -> map(resultSet)).optional();
    }

    @Override
    public WebhookDelivery save(WebhookDelivery delivery) {
        int updated = jdbc.sql("""
                UPDATE webhook_delivery SET status = :status, attempts = :attempts, next_attempt_at = COALESCE(:next, next_attempt_at),
                       last_http_status = :http, last_error = :error, delivered_at = :delivered, version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :version
                """)
                .param("status", delivery.status().name()).param("attempts", delivery.attempts())
                .param("next", delivery.nextAttemptAt() == null ? null : Timestamp.from(delivery.nextAttemptAt()))
                .param("http", delivery.lastHttpStatus()).param("error", delivery.lastError())
                .param("delivered", delivery.deliveredAt() == null ? null : Timestamp.from(delivery.deliveredAt()))
                .param("id", delivery.id()).param("version", delivery.version()).update();
        if (updated == 0) {
            throw new ConflictException(ErrorCodes.CONFLICT, "Entrega alterada por outra instância.");
        }
        return findById(delivery.id()).orElseThrow();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Corpo do webhook não serializável", exception);
        }
    }

    private static WebhookDelivery map(ResultSet resultSet) throws SQLException {
        Timestamp next = resultSet.getTimestamp("next_attempt_at");
        Timestamp delivered = resultSet.getTimestamp("delivered_at");
        int http = resultSet.getInt("last_http_status");
        boolean httpIsNull = resultSet.wasNull();                  // wasNull() só vale logo após ler a coluna
        return new WebhookDelivery(resultSet.getObject("id", UUID.class), resultSet.getObject("subscription_id", UUID.class),
                resultSet.getObject("source_event_id", UUID.class), resultSet.getString("event_type"), DeliveryStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempts"), next == null ? null : next.toInstant(), httpIsNull ? null : http, resultSet.getString("last_error"),
                delivered == null ? null : delivered.toInstant(), resultSet.getLong("version"));
    }
}
