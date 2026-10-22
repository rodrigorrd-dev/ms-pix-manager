package br.com.pixmanager.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;

/** Conversão {@link Instant} ↔ {@link Timestamp} para colunas opcionais: {@code NULL} no banco é {@code null} no domínio, e vice-versa. */
public final class Timestamps {

    private Timestamps() {}

    public static Timestamp from(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
