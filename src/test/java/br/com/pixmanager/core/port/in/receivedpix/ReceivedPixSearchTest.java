package br.com.pixmanager.core.port.in.receivedpix;

import br.com.pixmanager.core.port.in.receivedpix.FindReceivedPixUseCase.ReceivedPixSearch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceivedPixSearchTest {

    static final Instant T = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void acceptsAValidIntervalAndEqualBounds() {
        assertThat(new ReceivedPixSearch(T, T.plusSeconds(60), Optional.empty(), Optional.empty()).from()).isEqualTo(T);
        assertThat(new ReceivedPixSearch(T, T, Optional.of("tx"), Optional.empty()).txid()).contains("tx");
    }

    @Test
    void rejectsInvertedOrMissingBounds() {
        assertThatThrownBy(() -> new ReceivedPixSearch(T.plusSeconds(1), T, Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReceivedPixSearch(null, T, Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReceivedPixSearch(T, null, Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }
}
