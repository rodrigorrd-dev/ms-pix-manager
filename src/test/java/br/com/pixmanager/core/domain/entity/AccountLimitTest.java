package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.shared.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AccountLimit — BR-LIM-03/04: aumento só após a carência, redução imediata, teto do banco")
class AccountLimitTest {

    static final Duration DELAY = Duration.ofHours(24);
    static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");

    AccountLimit daily() {
        return AccountLimit.initial(UUID.randomUUID(), UUID.randomUUID(), LimitScope.DAILY, Money.of("5000.00"), Money.of("20000.00"));
    }

    @Test
    void increaseStaysPendingUntilTheWaitingPeriodPasses() {
        AccountLimit raised = daily().requestChange(Money.of("8000.00"), T0, DELAY);

        assertThat(raised.amount()).isEqualTo(Money.of("5000.00"));
        assertThat(raised.pendingAmount()).isEqualTo(Money.of("8000.00"));
        assertThat(raised.pendingEffectiveAt()).isEqualTo(T0.plus(DELAY));
        assertThat(raised.effective(T0)).isEqualTo(Money.of("5000.00"));
        assertThat(raised.effective(T0.plus(Duration.ofHours(23)).plusSeconds(3599))).isEqualTo(Money.of("5000.00"));
        assertThat(raised.effective(T0.plus(DELAY))).as("vale a partir de 24h exatas").isEqualTo(Money.of("8000.00"));
    }

    @Test
    void decreaseIsImmediateAndCancelsAnyPendingIncrease() {
        AccountLimit raised = daily().requestChange(Money.of("8000.00"), T0, DELAY);
        AccountLimit lowered = raised.requestChange(Money.of("3000.00"), T0.plusSeconds(60), DELAY);

        assertThat(lowered.effective(T0.plusSeconds(60))).isEqualTo(Money.of("3000.00"));
        assertThat(lowered.pendingAmount()).isNull();
        assertThat(lowered.effective(T0.plus(Duration.ofDays(3)))).as("o aumento cancelado não 'ressuscita'").isEqualTo(Money.of("3000.00"));
    }

    @Test
    void newIncreaseWhilePendingKeepsTheCurrentEffectiveAndRestartsTheWait() {
        AccountLimit first = daily().requestChange(Money.of("8000.00"), T0, DELAY);
        AccountLimit second = first.requestChange(Money.of("9000.00"), T0.plus(Duration.ofHours(1)), DELAY);
        assertThat(second.amount()).isEqualTo(Money.of("5000.00"));
        assertThat(second.pendingAmount()).isEqualTo(Money.of("9000.00"));
        assertThat(second.pendingEffectiveAt()).isEqualTo(T0.plus(Duration.ofHours(1)).plus(DELAY));
    }

    @Test
    void aboveBankCeilingIsRejectedWithItsOwnErrorCode() {
        assertThatThrownBy(() -> daily().requestChange(Money.of("20000.01"), T0, DELAY))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("PIX-422-052");
        assertThat(daily().requestChange(Money.of("20000.00"), T0, DELAY).pendingAmount()).isEqualTo(Money.of("20000.00"));
    }

    @Test
    void negativeLimitIsRejected() {
        assertThatThrownBy(() -> daily().requestChange(Money.of("-1.00"), T0, DELAY)).isInstanceOf(BusinessException.class);
    }

    @Test
    void sameValueIsANoOpReductionWithoutPending() {
        AccountLimit same = daily().requestChange(Money.of("5000.00"), T0, DELAY);
        assertThat(same.pendingAmount()).isNull();
        assertThat(same.effective(T0)).isEqualTo(Money.of("5000.00"));
    }
}
