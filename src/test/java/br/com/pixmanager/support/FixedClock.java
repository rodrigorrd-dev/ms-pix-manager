package br.com.pixmanager.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Relógio mutável para testes (o tempo nunca vem de Instant.now() solto). */
public final class FixedClock extends Clock {

    private Instant now;

    public FixedClock(Instant start) {
        this.now = start;
    }

    public static FixedClock at(String iso) {
        return new FixedClock(Instant.parse(iso));
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
