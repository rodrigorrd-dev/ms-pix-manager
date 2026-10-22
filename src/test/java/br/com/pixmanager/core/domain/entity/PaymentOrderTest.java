package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.domain.enums.PaymentStatus;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.InvalidTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PaymentOrder — máquina de estados (BR-PAY-06)")
class PaymentOrderTest {

    static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");

    PaymentOrder order(String amount) {
        return PaymentOrder.create(UUID.randomUUID(), UUID.randomUUID(), PaymentMethod.KEY, PaymentOrigin.IMMEDIATE,
                new PaymentDestination(null, null, null, "Favorecido", "87654321", "0002", "55555", "CACC"), Money.of(amount), null, null,
                new EndToEndId("E12345678202610151500abcdefghijk"), true, NOW);
    }

    @Test
    void happyPathCreatedToSettled() {
        PaymentOrder paymentOrder = order("10.00").validated(UUID.randomUUID()).reserved().sentToSpi(NOW).settled(NOW.plusSeconds(2));
        assertThat(paymentOrder.status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(paymentOrder.ledgerReservationId()).isNotNull();
        assertThat(paymentOrder.sentAt()).isEqualTo(NOW);
        assertThat(paymentOrder.settledAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void settlementWithoutSentAckIsAllowedFromReserved() {
        assertThat(order("1.00").validated(UUID.randomUUID()).reserved().settled(NOW).status()).isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    void finalStatesAreImmutable() {
        PaymentOrder settled = order("1.00").validated(UUID.randomUUID()).reserved().settled(NOW);
        assertThatThrownBy(() -> settled.rejected("AM04", "x")).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(settled::cancelled).isInstanceOf(InvalidTransitionException.class);
        PaymentOrder rejected = order("1.00").rejected("AM04", "sem saldo");
        assertThatThrownBy(() -> rejected.validated(UUID.randomUUID())).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    @DisplayName("Depois de ir ao SPI a ordem NÃO pode ser cancelada")
    void cannotCancelOnceSentToSpi() {
        PaymentOrder sent = order("1.00").validated(UUID.randomUUID()).reserved().sentToSpi(NOW);
        assertThatThrownBy(sent::cancelled).isInstanceOf(InvalidTransitionException.class);
        assertThat(order("1.00").validated(UUID.randomUUID()).reserved().cancelled().status()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void repeatingTheSameTransitionIsIdempotent() {
        PaymentOrder reserved = order("1.00").validated(UUID.randomUUID()).reserved();
        assertThat(reserved.reserved()).isSameAs(reserved);
    }

    @Test
    void rejectionCarriesTheCode() {
        PaymentOrder paymentOrder = order("1.00").validated(UUID.randomUUID()).rejected("AM04", "saldo");
        assertThat(paymentOrder.rejectReasonCode()).isEqualTo("AM04");
        assertThat(paymentOrder.rejectReasonDetail()).isEqualTo("saldo");
    }

    @Test
    void amountAndMessageAreValidated() {
        assertThatThrownBy(() -> order("0.00")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> PaymentOrder.create(UUID.randomUUID(), UUID.randomUUID(), PaymentMethod.MANUAL, PaymentOrigin.IMMEDIATE,
                new PaymentDestination(null, null, null, "x", "87654321", "0002", "1", "CACC"), Money.of("1.00"), null, "x".repeat(141),
                new EndToEndId("E12345678202610151500abcdefghijk"), true, NOW)).isInstanceOf(BadRequestException.class);
    }
}
