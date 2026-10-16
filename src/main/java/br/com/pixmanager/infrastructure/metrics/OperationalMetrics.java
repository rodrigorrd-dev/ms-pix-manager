package br.com.pixmanager.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Métricas de "trabalho preso" que alimentam os alertas operacionais (ops/prometheus/alert-rules.yml). Calculadas por consulta ao banco a cada
 * 30 s (as consultas NÃO rodam no scrape do Prometheus) e expostas como gauges:
 * <ul>
 *   <li>{@code pix_payment_orders_stuck{status}} — ordens em VALIDATED/RESERVED/SENT_TO_SPI sem avançar há mais de {@code stuck-after};</li>
 *   <li>{@code pix_pixkeys_pending_dict_stuck} — chaves em PENDING_DICT há mais de {@code stuck-after} (DICT indisponível?);</li>
 *   <li>{@code pix_ledger_reservations_pending_stuck} — reservas PENDING no core há mais de {@code stuck-after};</li>
 *   <li>{@code pix_webhook_deliveries_dead} — entregas de webhook que esgotaram as tentativas (o cliente não foi avisado do Pix);</li>
 *   <li>{@code pix_webhook_deliveries_overdue} — entregas PENDING/RETRYING vencidas há mais de {@code stuck-after} (job parado ou receptores lentos);</li>
 *   <li>{@code pix_outbox_rows} — linhas na outbox (cresce se a limpeza ou o Debezium pararem);</li>
 *   <li>{@code pix_outbox_slot_lag_bytes} — atraso do slot do Debezium no WAL (precisa de permissão para ler o WAL; sem ela fica fora, e em
 *       produção use o postgres_exporter).</li>
 * </ul>
 */
@Component
public class OperationalMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);
    private static final List<String> PAYMENT_STATUSES = List.of("VALIDATED", "RESERVED", "SENT_TO_SPI");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration stuckAfter;
    private final String slotName;

    private final Map<String, AtomicLong> paymentsStuck = new ConcurrentHashMap<>();
    private final AtomicLong keysStuck = new AtomicLong();
    private final AtomicLong reservationsStuck = new AtomicLong();
    private final AtomicLong webhookDead = new AtomicLong();
    private final AtomicLong webhookOverdue = new AtomicLong();
    private final AtomicLong outboxRows = new AtomicLong();
    private final AtomicLong slotLag = new AtomicLong(-1);

    public OperationalMetrics(JdbcClient jdbc, Clock clock,
                              @Value("${pix.metrics.stuck-after:PT5M}") Duration stuckAfter,
                              @Value("${pix.metrics.outbox-slot-name:pix_outbox_slot}") String slotName) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
        this.slotName = slotName;
        PAYMENT_STATUSES.forEach(s -> paymentsStuck.put(s, new AtomicLong()));
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        paymentsStuck.forEach((status, value) ->
                Gauge.builder("pix.payment.orders.stuck", value, AtomicLong::get).tag("status", status).register(registry));
        Gauge.builder("pix.pixkeys.pending.dict.stuck", keysStuck, AtomicLong::get).register(registry);
        Gauge.builder("pix.ledger.reservations.pending.stuck", reservationsStuck, AtomicLong::get).register(registry);
        Gauge.builder("pix.webhook.deliveries.dead", webhookDead, AtomicLong::get).register(registry);
        Gauge.builder("pix.webhook.deliveries.overdue", webhookOverdue, AtomicLong::get).register(registry);
        Gauge.builder("pix.outbox.rows", outboxRows, AtomicLong::get).register(registry);
        Gauge.builder("pix.outbox.slot.lag.bytes", slotLag, AtomicLong::get).register(registry);   // -1 = indisponível
    }

    @Scheduled(initialDelay = 10_000, fixedDelayString = "${pix.metrics.refresh-delay:PT30S}")
    void refresh() {
        try {
            Instant cutoff = clock.instant().minus(stuckAfter);
            for (String status : PAYMENT_STATUSES) {
                paymentsStuck.get(status).set(count("SELECT count(*) FROM payment_order WHERE status = :s AND updated_at < :c", status, cutoff));
            }
            keysStuck.set(count("SELECT count(*) FROM pix_key WHERE status = :s AND updated_at < :c", "PENDING_DICT", cutoff));
            reservationsStuck.set(count("SELECT count(*) FROM ledger_reservation WHERE status = :s AND updated_at < :c", "PENDING", cutoff));
            webhookDead.set(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE status = 'DEAD'").query(Long.class).single());
            webhookOverdue.set(jdbc.sql("SELECT count(*) FROM webhook_delivery WHERE status IN ('PENDING','RETRYING') AND next_attempt_at < :c")
                    .param("c", java.sql.Timestamp.from(cutoff)).query(Long.class).single());
            outboxRows.set(jdbc.sql("SELECT count(*) FROM outbox_event").query(Long.class).single());
        } catch (RuntimeException e) {
            log.warn("Falha ao atualizar métricas operacionais: {}", e.getMessage());
        }
        refreshSlotLag();
    }

    private long count(String sql, String status, Instant cutoff) {
        return jdbc.sql(sql).param("s", status).param("c", java.sql.Timestamp.from(cutoff)).query(Long.class).single();
    }

    private void refreshSlotLag() {
        try {
            Long lag = jdbc.sql("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)::bigint FROM pg_replication_slots WHERE slot_name = :n")
                    .param("n", slotName).query(Long.class).optional().orElse(-1L);
            slotLag.set(lag);
        } catch (RuntimeException e) {
            slotLag.set(-1);                 // sem permissão para ler o WAL (usuário não privilegiado): use o postgres_exporter
        }
    }
}
