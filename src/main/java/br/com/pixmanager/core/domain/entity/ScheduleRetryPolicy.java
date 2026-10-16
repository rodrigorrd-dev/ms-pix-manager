package br.com.pixmanager.core.domain.entity;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** BR-SCH-03: nova tentativa NO MESMO DIA entre 18h e 21h (fuso configurável) com jitter; sem janela restante ⇒ falha definitiva. */
public record ScheduleRetryPolicy(ZoneId zone, LocalTime windowStart, LocalTime windowEnd, int maxAttempts) {

    public static ScheduleRetryPolicy standard(ZoneId zone) {
        return new ScheduleRetryPolicy(zone, LocalTime.of(18, 0), LocalTime.of(21, 0), 2);
    }

    /**
     * @param jitter0to1 número em [0,1) injetado (testável); posiciona a tentativa dentro da janela restante.
     * @return instante da próxima tentativa, ou {@code null} se o dia já não comporta (após o fim da janela) ou esgotou as tentativas.
     */
    public Instant nextAttempt(LocalDate dueDate, Instant now, int attemptsSoFar, double jitter0to1) {
        if (attemptsSoFar >= maxAttempts) {
            return null;
        }
        Instant start = dueDate.atTime(windowStart).atZone(zone).toInstant();
        Instant end = dueDate.atTime(windowEnd).atZone(zone).toInstant();
        Instant from = now.isAfter(start) ? now : start;
        if (!from.isBefore(end)) {
            return null;
        }
        long span = Duration.between(from, end).toSeconds();
        return from.plusSeconds((long) (span * Math.min(Math.max(jitter0to1, 0), 0.999999)));
    }
}
