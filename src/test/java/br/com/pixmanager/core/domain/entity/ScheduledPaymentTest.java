package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.enums.Recurrence;
import br.com.pixmanager.shared.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScheduledPayment / Recurrence — datas das ocorrências e validações (BR-SCH-01)")
class ScheduledPaymentTest {

    static final LocalDate TODAY = LocalDate.parse("2026-10-15");

    static ScheduledPayment of(Recurrence r, String first, String end, Integer total) {
        return ScheduledPayment.create(UUID.randomUUID(), UUID.randomUUID(), r, PaymentMethod.KEY, PixKeyType.EMAIL, "a@b.com", null, null, null,
                null, null, null, Money.of("10.00"), null, LocalDate.parse(first), end == null ? null : LocalDate.parse(end), total, TODAY, true);
    }

    @ParameterizedTest(name = "{0} a partir de {1} ×{2} ⇒ última {3}")
    @CsvSource({"WEEKLY,2026-10-16,4,2026-11-06", "BIWEEKLY,2026-10-16,3,2026-11-13", "MONTHLY,2027-01-31,3,2027-03-31",
            "BIMONTHLY,2026-10-16,3,2027-02-16", "QUARTERLY,2026-10-16,2,2027-01-16", "SEMIANNUAL,2026-10-16,2,2027-04-16", "ANNUAL,2026-10-16,2,2027-10-16"})
    void shouldComputeDatesFromTheFirstDate(Recurrence r, String first, int total, String last) {
        List<LocalDate> dates = of(r, first, null, total).occurrenceDates();

        assertThat(dates).hasSize(total).endsWith(LocalDate.parse(last)).isSorted();
    }

    @Test
    @DisplayName("Mensal no dia 31 clampa para o fim do mês mas volta ao 31 depois (não encadeia)")
    void shouldNotChainMonthClamping() {
        assertThat(of(Recurrence.MONTHLY, "2026-10-31", null, 3).occurrenceDates())
                .containsExactly(LocalDate.parse("2026-10-31"), LocalDate.parse("2026-11-30"), LocalDate.parse("2026-12-31"));
    }

    @Test
    void shouldCountDatesUpToTheEndDateInclusive() {
        assertThat(of(Recurrence.WEEKLY, "2026-10-16", "2026-11-06", null).occurrenceDates()).hasSize(4);
        assertThat(of(Recurrence.ONCE, "2026-10-16", null, null).occurrenceDates()).containsExactly(LocalDate.parse("2026-10-16"));
    }

    @Test
    @DisplayName("Regras inválidas ⇒ PIX-422-061")
    void shouldRejectInvalidSchedules() {
        assertThatThrownBy(() -> of(Recurrence.ONCE, "2026-10-15", null, null)).isInstanceOf(BusinessException.class).hasMessageContaining("posterior a hoje");
        assertThatThrownBy(() -> of(Recurrence.ONCE, "2026-10-16", null, 2)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> of(Recurrence.WEEKLY, "2026-10-16", null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> of(Recurrence.WEEKLY, "2026-10-16", "2026-11-01", 3)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> of(Recurrence.WEEKLY, "2026-10-16", "2026-10-10", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> of(Recurrence.WEEKLY, "2026-10-16", null, 1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> of(Recurrence.WEEKLY, "2026-10-16", null, 121)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> of(Recurrence.WEEKLY, "2026-10-16", "2036-10-16", null)).isInstanceOf(BusinessException.class).hasMessageContaining("120");
    }

    @Test
    void shouldAcceptOnlyKeyAndManualAndRequireTheirData() {
        assertThatThrownBy(() -> ScheduledPayment.create(UUID.randomUUID(), UUID.randomUUID(), Recurrence.ONCE, PaymentMethod.QR_STATIC, null, null,
                null, null, null, null, null, null, Money.of("1.00"), null, TODAY.plusDays(1), null, null, TODAY, true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ScheduledPayment.create(UUID.randomUUID(), UUID.randomUUID(), Recurrence.ONCE, PaymentMethod.KEY, null, null,
                null, null, null, null, null, null, Money.of("1.00"), null, TODAY.plusDays(1), null, null, TODAY, true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ScheduledPayment.create(UUID.randomUUID(), UUID.randomUUID(), Recurrence.ONCE, PaymentMethod.MANUAL, null, null,
                "123", "0001", "1", "CACC", "Fulano", null, Money.of("1.00"), null, TODAY.plusDays(1), null, null, TODAY, true)).isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldCancelOnlyWhileActive() {
        ScheduledPayment cancelled = of(Recurrence.ONCE, "2026-10-16", null, null).cancelled();

        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        assertThatThrownBy(cancelled::cancelled).hasMessageContaining("não pode ser cancelado");
        assertThat(cancelled.completed()).as("cancelado não vira concluído").isSameAs(cancelled);
    }
}
