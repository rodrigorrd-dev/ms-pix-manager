package br.com.pixmanager.infrastructure.generator;

import br.com.pixmanager.shared.properties.InstitutionProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdGeneratorsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T14:03:00Z"), ZoneOffset.UTC);

    @Test
    void uuidV7ShouldHaveVersionVariantAndBeTimeOrdered() {
        UuidV7IdGeneratorAdapter generator = new UuidV7IdGeneratorAdapter(clock);
        UUID id = generator.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(clock.millis());

        UuidV7IdGeneratorAdapter later = new UuidV7IdGeneratorAdapter(Clock.fixed(clock.instant().plusSeconds(1), ZoneOffset.UTC));
        assertThat(later.newId().toString().compareTo(id.toString())).isPositive();
    }

    @Test
    void uuidV7ShouldNotCollide() {
        UuidV7IdGeneratorAdapter generator = new UuidV7IdGeneratorAdapter(clock);
        Set<UUID> ids = new HashSet<>();
        for (int index = 0; index < 20_000; index++) {
            assertThat(ids.add(generator.newId())).isTrue();
        }
    }

    @Test
    void endToEndIdShouldFollowPixFormatWithConfiguredIspb() {
        EndToEndIdGeneratorAdapter generator = new EndToEndIdGeneratorAdapter(new InstitutionProperties("12345678", "Banco"), clock);
        String endToEndId = generator.nextEndToEndId();
        assertThat(endToEndId).hasSize(32).matches("[a-zA-Z0-9]{32}").startsWith("E12345678202610151403");
        assertThat(generator.nextEndToEndId()).isNotEqualTo(endToEndId);
    }

    @Test
    void ispbIsRequiredFromConfigurationNeverHardcoded() {
        assertThatThrownBy(() -> new InstitutionProperties("", "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InstitutionProperties("123", "x")).isInstanceOf(IllegalStateException.class);
    }
}
