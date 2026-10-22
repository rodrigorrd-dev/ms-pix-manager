package br.com.pixmanager.infrastructure.generator;

import br.com.pixmanager.core.port.out.generator.EndToEndIdGeneratorPortOut;
import br.com.pixmanager.shared.properties.InstitutionProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Ids do SPI: prefixo ({@code E} pagamento / {@code D} devolução) + ISPB(8, de CONFIGURAÇÃO) + {@code yyyyMMddHHmm} UTC (12)
 * + 11 alfanuméricos = 32 caracteres. O {@code endToEndId}/{@code rtrId} é a chave de idempotência do core (decisão A2).
 */
@Component
public class EndToEndIdGeneratorAdapter implements EndToEndIdGeneratorPortOut {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final InstitutionProperties institution;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EndToEndIdGeneratorAdapter(InstitutionProperties institution, Clock clock) {
        this.institution = institution;
        this.clock = clock;
    }

    @Override
    public String nextEndToEndId() {
        return next('E');
    }

    @Override
    public String nextReturnId() {
        return next('D');
    }

    private String next(char prefix) {
        StringBuilder builder = new StringBuilder(32).append(prefix).append(institution.ispb()).append(STAMP.format(clock.instant()));
        for (int index = 0; index < 11; index++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
