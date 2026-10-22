package br.com.pixmanager.infrastructure.generator;

import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562): 48 bits de timestamp em ms + aleatório. Ordenável por tempo ⇒ menos fragmentação de índice B-tree
 * que o v4 (docs/04 §3.1). Java 21 não tem v7 nativo.
 */
@Component
public class UuidV7IdGeneratorAdapter implements IdGeneratorPortOut {

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public UuidV7IdGeneratorAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public UUID newId() {
        long millis = clock.millis();
        byte[] rnd = new byte[10];
        random.nextBytes(rnd);
        long msb = (millis << 16) | 0x7000L | ((rnd[0] & 0x0FL) << 8) | (rnd[1] & 0xFFL);
        long lsb = 0L;
        for (int index = 2; index < 10; index++) {
            lsb = (lsb << 8) | (rnd[index] & 0xFF);
        }
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;   // variante RFC 4122
        return new UUID(msb, lsb);
    }
}
