package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.OccurrenceStatus;
import br.com.pixmanager.core.domain.enums.Recurrence;
import br.com.pixmanager.shared.exception.InvalidTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Pix Agendado — recorrência, ocorrência e janela de retentativa (BR-SCH-01..05)")
class ScheduledOccurrenceTest {

    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    ScheduleRetryPolicy policy = ScheduleRetryPolicy.standard(SP);

    ScheduledOccurrence occ() {
        return ScheduledOccurrence.scheduled(UUID.randomUUID(), UUID.randomUUID(), 1, DAY);
    }

    @Test
    void monthlyClampsToLastDayAndDoesNotDrift() {
        LocalDate first = LocalDate.of(2026, 1, 31);
        assertThat(Recurrence.MONTHLY.dateOf(first, 2)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Recurrence.MONTHLY.dateOf(first, 3)).as("calculada da 1ª, não da anterior").isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(Recurrence.WEEKLY.dateOf(first, 3)).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(Recurrence.BIWEEKLY.dateOf(first, 2)).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(Recurrence.QUARTERLY.dateOf(first, 2)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(Recurrence.ANNUAL.dateOf(LocalDate.of(2024, 2, 29), 2)).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(Recurrence.ONCE.dateOf(first, 1)).isEqualTo(first);
        assertThatThrownBy(() -> Recurrence.MONTHLY.dateOf(first, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dueOnlyOnOrAfterTheDayAndWhenRetryTimeArrived() {
        Instant noon = DAY.atTime(12, 0).atZone(SP).toInstant();
        assertThat(occ().isDue(DAY.minusDays(1), noon)).isFalse();
        assertThat(occ().isDue(DAY, noon)).isTrue();
        ScheduledOccurrence retry = occ().startProcessing(UUID.randomUUID()).insufficientFunds(noon.plusSeconds(3600), noon);
        assertThat(retry.isDue(DAY, noon)).isFalse();
        assertThat(retry.isDue(DAY, noon.plusSeconds(3601))).isTrue();
    }

    @Test
    @DisplayName("Saldo insuficiente às 10h: retenta dentro de 18h–21h do MESMO dia e avisa o cliente")
    void retriesInsideTheWindowAndNotifiesOnce() {
        Instant now = DAY.atTime(10, 0).atZone(SP).toInstant();
        Instant retryAt = policy.nextAttempt(DAY, now, 1, 0.5);
        assertThat(retryAt.atZone(SP).toLocalDate()).isEqualTo(DAY);
        assertThat(retryAt.atZone(SP).toLocalTime()).isBetween(java.time.LocalTime.of(18, 0), java.time.LocalTime.of(21, 0));
        ScheduledOccurrence r = occ().startProcessing(UUID.randomUUID()).insufficientFunds(retryAt, now);
        assertThat(r.status()).isEqualTo(OccurrenceStatus.RETRY_PENDING);
        assertThat(r.customerNotifiedAt()).isEqualTo(now);
        ScheduledOccurrence second = r.startProcessing(UUID.randomUUID()).insufficientFunds(null, now.plusSeconds(9 * 3600));
        assertThat(second.status()).isEqualTo(OccurrenceStatus.FAILED_INSUFFICIENT_FUNDS);
        assertThat(second.customerNotifiedAt()).as("aviso só uma vez").isEqualTo(now);
        assertThat(second.attemptCount()).isEqualTo(2);
    }

    @Test
    void noRetryAfterTheWindowOrWhenAttemptsAreExhausted() {
        assertThat(policy.nextAttempt(DAY, DAY.atTime(21, 0).atZone(SP).toInstant(), 1, 0.1)).isNull();
        assertThat(policy.nextAttempt(DAY, DAY.atTime(22, 30).atZone(SP).toInstant(), 1, 0.1)).isNull();
        assertThat(policy.nextAttempt(DAY, DAY.atTime(10, 0).atZone(SP).toInstant(), 2, 0.1)).isNull();
        Instant inside = DAY.atTime(19, 0).atZone(SP).toInstant();
        assertThat(policy.nextAttempt(DAY, inside, 1, 0.0)).isEqualTo(inside);
        assertThat(policy.nextAttempt(DAY, inside, 1, 5.0)).isBefore(DAY.atTime(21, 0).atZone(SP).toInstant());
    }

    @Test
    void processingCannotBeCancelledAndFinalStatesAreImmutable() {
        ScheduledOccurrence processing = occ().startProcessing(UUID.randomUUID());
        assertThatThrownBy(processing::cancelled).isInstanceOf(InvalidTransitionException.class);
        assertThat(occ().cancelled().status()).isEqualTo(OccurrenceStatus.CANCELLED);
        ScheduledOccurrence done = processing.executed();
        assertThatThrownBy(() -> done.startProcessing(UUID.randomUUID())).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(done::cancelled).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> occ().executed()).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void otherFailuresAreDefinitive() {
        ScheduledOccurrence f = occ().startProcessing(UUID.randomUUID()).failed("PIX-422-040");
        assertThat(f.status()).isEqualTo(OccurrenceStatus.FAILED);
        assertThat(f.lastErrorCode()).isEqualTo("PIX-422-040");
    }
}
