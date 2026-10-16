package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.LimitDecision;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentDestination;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.event.PaymentOrderEvent;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.domain.enums.PaymentStatus;
import br.com.pixmanager.core.port.in.ledger.ExecuteReservationUseCase;
import br.com.pixmanager.core.port.in.limit.ConsumeLimitUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.exception.InvalidTransitionException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.model.ExpectedVersion;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CancelPaymentOrderService — valida a transição ANTES de qualquer I/O; core fora da TX; persiste + limite + evento na TX")
class CancelPaymentOrderServiceTest {

    static final String E2E = "E12345678202610151403abcdefghij0";
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final Instant REQUESTED = Instant.parse("2026-10-15T01:30:00Z");   // 22:30 de 14/10 em SP

    @Mock PaymentOrderPersistencePortOut persistence;
    @Mock ExecuteReservationUseCase ledger;
    @Mock ConsumeLimitUseCase limits;
    @Mock DomainEventPublisherPortOut events;

    final UUID payerId = SequentialIds.id(100);
    final UUID orderId = SequentialIds.id(1);
    final Caller caller = Caller.forAccounts("client", payerId);
    CancelPaymentOrderService service;

    @BeforeEach
    void setUp() {
        TransactionRunnerPortOut tx = new TransactionRunnerPortOut() {
            @Override
            public <T> T inTransaction(Supplier<T> action) {
                return action.get();
            }
        };
        service = new CancelPaymentOrderService(persistence, ledger, limits, events, tx, SP);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CREATED", "VALIDATED", "RESERVED"})
    @DisplayName("Cancela no core, grava CANCELLED, devolve o limite do dia (SP) e publica PaymentCancelled")
    void shouldCancelBeforeTheSpiAndReleaseTheLimit(PaymentStatus status) {
        when(persistence.findById(orderId)).thenReturn(Optional.of(order(status, 3, false)));
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentOrder result = service.cancel(caller, orderId, new ExpectedVersion.Exact(3));

        assertThat(result.status()).isEqualTo(PaymentStatus.CANCELLED);
        InOrder order = inOrder(ledger, persistence);
        order.verify(ledger).cancel(E2E);
        order.verify(persistence).save(any(PaymentOrder.class));
        var released = ArgumentCaptor.forClass(LimitDecision.Allowed.class);
        verify(limits).release(org.mockito.ArgumentMatchers.eq(payerId), released.capture());
        assertThat(released.getValue().day()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(released.getValue().deviceDailyConsumed()).isTrue();
        var event = ArgumentCaptor.forClass(PaymentOrderEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("PaymentCancelled");
    }

    @Test
    @DisplayName("If-Match '*' aceita qualquer versão")
    void shouldAcceptAnyVersionWhenIfMatchIsWildcard() {
        when(persistence.findById(orderId)).thenReturn(Optional.of(order(PaymentStatus.RESERVED, 9, true)));
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.cancel(caller, orderId, new ExpectedVersion.Any()).status()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SENT_TO_SPI", "SETTLED", "REJECTED"})
    @DisplayName("Depois do SPI não cancela, e nenhum I/O acontece antes da recusa")
    void shouldRefuseOnceTheOrderLeftForTheSpiWithoutAnyIo(PaymentStatus status) {
        when(persistence.findById(orderId)).thenReturn(Optional.of(order(status, 0, true)));

        assertThatThrownBy(() -> service.cancel(caller, orderId, new ExpectedVersion.Any()))
                .as("status %s", status)
                .isInstanceOf(InvalidTransitionException.class);

        verifyNoInteractions(ledger, limits, events);
        verify(persistence, never()).save(any());
    }

    @Test
    void shouldFailWithPreconditionFailedWhenTheVersionIsStale() {
        when(persistence.findById(orderId)).thenReturn(Optional.of(order(PaymentStatus.RESERVED, 5, true)));

        assertThatThrownBy(() -> service.cancel(caller, orderId, new ExpectedVersion.Exact(4)))
                .isInstanceOf(PreconditionFailedException.class);

        verifyNoInteractions(ledger, limits, events);
    }

    @Test
    void shouldHideAnOrderOfAnotherAccountAsNotFound() {
        Caller other = Caller.forAccounts("other", SequentialIds.id(99));
        when(persistence.findById(orderId)).thenReturn(Optional.of(order(PaymentStatus.RESERVED, 0, true)));

        assertThatThrownBy(() -> service.cancel(other, orderId, new ExpectedVersion.Any())).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(ledger, limits, events);
    }

    @Test
    void shouldReturnNotFoundWhenTheOrderDoesNotExist() {
        when(persistence.findById(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(caller, orderId, new ExpectedVersion.Any())).isInstanceOf(NotFoundException.class);
    }

    private PaymentOrder order(PaymentStatus status, long version, boolean deviceRegistered) {
        return new PaymentOrder(orderId, payerId, PaymentMethod.MANUAL, PaymentOrigin.IMMEDIATE,
                new PaymentDestination(null, null, null, "Favorecido", "87654321", "0002", "98765", "CACC"), Money.of("80.00"), null, null,
                new EndToEndId(E2E), status, null, null, UUID.randomUUID(), deviceRegistered, REQUESTED, null, null, version);
    }
}
