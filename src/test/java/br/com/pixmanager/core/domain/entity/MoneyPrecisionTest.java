package br.com.pixmanager.core.domain.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyPrecisionTest {

    @Test
    void neverRoundsSilently() {
        assertThatThrownBy(() -> Money.of("10.005")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTrailingZerosBeyondTwoDecimals() {
        assertThat(Money.of("10.500").toBcb()).isEqualTo("10.50");
        assertThat(Money.of("7").toBcb()).isEqualTo("7.00");
    }
}
