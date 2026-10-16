package br.com.pixmanager.infrastructure.schedule;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

/**
 * Limpa a {@code outbox_event}. O Debezium lê o WAL (não a tabela), então apagar linhas antigas é seguro: a inserção já está no log e o
 * EventRouter ignora os DELETEs. Sem esta limpeza a tabela cresce sem limite (≈ uma linha por evento de domínio, para sempre).
 * Retenção padrão de 7 dias ({@code pix.outbox.retention}); apaga em lotes para não segurar lock longo. ShedLock: um por cluster.
 */
@Component
public class OutboxCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    OutboxCleanupJob(JdbcClient jdbc, Clock clock, @Value("${pix.outbox.retention:P7D}") Duration retention,
                     @Value("${pix.outbox.cleanup-batch:5000}") int batchSize) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelay = 60_000, fixedDelayString = "${pix.outbox.cleanup-delay:PT10M}")
    @SchedulerLock(name = "outbox-cleanup", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    void run() {
        cleanup();
    }

    /** @return linhas removidas nesta execução. */
    int cleanup() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(retention));
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.sql("DELETE FROM outbox_event WHERE id IN (SELECT id FROM outbox_event WHERE created_at < :cutoff ORDER BY created_at LIMIT :n)")
                    .param("cutoff", cutoff).param("n", batchSize).update();
            total += deleted;
        } while (deleted == batchSize);
        if (total > 0) {
            log.info("outbox: {} linha(s) com mais de {} removidas", total, retention);
        }
        return total;
    }
}
