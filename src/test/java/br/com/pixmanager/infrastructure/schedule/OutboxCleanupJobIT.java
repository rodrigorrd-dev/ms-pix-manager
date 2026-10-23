package br.com.pixmanager.infrastructure.schedule;

import br.com.pixmanager.support.integration.AbstractApplicationIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Limpeza da outbox_event: remove só o que passou da retenção, em lotes")
class OutboxCleanupJobIT extends AbstractApplicationIT {

    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    @Test
    @DisplayName("linhas antigas somem (vários lotes); recentes ficam")
    void shouldDeleteOnlyExpiredRows() {
        String marker = "cleanup-" + UUID.randomUUID();
        for (int index = 0; index < 7; index++) {
            insert(marker, Duration.ofDays(8));            // fora da retenção de 7 dias
        }
        UUID fresh = insert(marker, Duration.ofDays(1));    // dentro
        OutboxCleanupJob job = new OutboxCleanupJob(jdbc, clock, Duration.ofDays(7), 3);   // lote de 3 ⇒ 3 execuções do DELETE

        int removed = job.cleanup();

        assertThat(removed).isGreaterThanOrEqualTo(7);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = :m").param("m", marker).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE id = :id").param("id", fresh).query(Integer.class).single()).isEqualTo(1);
        assertThat(job.cleanup()).isZero();                 // idempotente
    }

    private UUID insert(String aggregateId, Duration age) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, event_version, payload, created_at) VALUES (:id, 'charge', :a, 'T', 1, :p, :c)")
                .param("id", id).param("a", aggregateId).param("p", new byte[]{1}).param("c", Timestamp.from(clock.instant().minus(age))).update();
        return id;
    }
}
