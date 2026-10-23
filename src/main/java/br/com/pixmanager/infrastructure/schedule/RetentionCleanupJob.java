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
 * Retenção das tabelas que só crescem: {@code idempotency_record} (já expirado), {@code inbox_message} (passou da retenção do tópico) e
 * {@code webhook_delivery} terminada (entregue ou morta). Apaga em lotes para não segurar lock longo; os índices
 * {@code idx_idempotency_expires}, {@code idx_inbox_processed} e {@code idx_webhook_delivery_finished} servem a cada DELETE. ShedLock: um por cluster.
 */
@Component
public class RetentionCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupJob.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration inboxRetention;
    private final Duration deliveredRetention;
    private final Duration deadRetention;
    private final int batchSize;

    RetentionCleanupJob(JdbcClient jdbc, Clock clock,
                        @Value("${pix.retention.inbox:P14D}") Duration inboxRetention,
                        @Value("${pix.retention.webhook-delivered:P30D}") Duration deliveredRetention,
                        @Value("${pix.retention.webhook-dead:P90D}") Duration deadRetention,
                        @Value("${pix.retention.cleanup-batch:5000}") int batchSize) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.inboxRetention = inboxRetention;
        this.deliveredRetention = deliveredRetention;
        this.deadRetention = deadRetention;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelay = 120_000, fixedDelayString = "${pix.retention.cleanup-delay:PT1H}")
    @SchedulerLock(name = "retention-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void run() {
        cleanup();
    }

    /** @return linhas removidas nesta execução (todas as tabelas somadas). */
    int cleanup() {
        Timestamp now = Timestamp.from(clock.instant());
        int idempotency = purge("idempotency_record", "SELECT id FROM idempotency_record WHERE expires_at < :cutoff LIMIT :n", now);
        int inbox = purge("inbox_message", "SELECT id FROM inbox_message WHERE processed_at < :cutoff LIMIT :n", Timestamp.from(clock.instant().minus(inboxRetention)));
        int delivered = purge("webhook_delivery", "SELECT id FROM webhook_delivery WHERE status = 'DELIVERED' AND updated_at < :cutoff LIMIT :n",
                Timestamp.from(clock.instant().minus(deliveredRetention)));
        int dead = purge("webhook_delivery", "SELECT id FROM webhook_delivery WHERE status = 'DEAD' AND updated_at < :cutoff LIMIT :n",
                Timestamp.from(clock.instant().minus(deadRetention)));
        int total = idempotency + inbox + delivered + dead;
        if (total > 0) {
            log.info("retenção: {} idempotência, {} inbox, {} webhooks entregues, {} webhooks mortos removidos", idempotency, inbox, delivered, dead);
        }
        return total;
    }

    private int purge(String table, String selectIds, Timestamp cutoff) {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.sql("DELETE FROM " + table + " WHERE id IN (" + selectIds + ")").param("cutoff", cutoff).param("n", batchSize).update();
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
