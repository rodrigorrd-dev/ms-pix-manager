package br.com.pixmanager.support;

import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Ids determinísticos: {@code 00000000-0000-0000-0000-000000000001}, … */
public final class SequentialIds implements IdGeneratorPortOut {

    private final AtomicLong counter = new AtomicLong();

    @Override
    public UUID newId() {
        return new UUID(0L, counter.incrementAndGet());
    }

    public static UUID id(long n) {
        return new UUID(0L, n);
    }
}
