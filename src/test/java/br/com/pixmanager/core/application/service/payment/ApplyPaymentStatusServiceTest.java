package br.com.pixmanager.core.application.service.payment;

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
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase.SpiStatus;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.event.InboxPortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.exception.NotFoundException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApplyPaymentStatusService (pacs.002) — core primeiro (fora da TX); depois inbox + ordem + limite + evento atômicos")
class ApplyPaymentStatusServiceTest {

    static final String E2E = "E12345678202610151403abcdefghij0";
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final Instant REQUESTED = Instant.parse("2026-10-15T01:30:00Z");   // 22:30 de 14/10 em SP
    static final Instant AT = Instant.parse("2026-10-15T01:31:00Z");

    @Mock PaymentOrderPersistencePortOut persistence;
    @Mock ExecuteReservationUseCase ledger;
    @Mock ConsumeLimitUseCase limits;
    @Mock DomainEventPublisherPortOut events;
    @Mock InboxPortOut inbox;

    ApplyPaymentStatusService service;

    @BeforeEach
    void setUp() {
        TransactionRunnerPortOut transactionRunner = new TransactionRunnerPortOut() {
            @Override
            public <T> T inTransaction(Supplier<T> action) {
                return action.get();
            }
        };
        service = new ApplyPaymentStatusService(persistence, ledger, limits, events, inbox, transactionRunner, SP);
    }

    @Test
    void shouldConfirmInTheLedgerThenSettleAndPublishWhenSpiSettles() {
        PaymentOrder sent = order(PaymentStatus.SENT_TO_SPI, true);
        stubOrder(sent);
        firstDelivery();
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        apply(SpiStatus.SETTLED, null);

        InOrder order = inOrder(ledger, persistence);
        order.verify(ledger).confirm(E2E);
        order.verify(persistence).save(any(PaymentOrder.class));
        var saved = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(persistence).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(saved.getValue().settledAt()).isEqualTo(AT);
        assertThat(publishedEvent().eventType()).isEqualTo("PaymentSettled");
        verify(limits, never()).release(any(), any());
    }

    @Test
    @DisplayName("Rejeição: cancela a reserva, grava REJECTED com o código do SPI e devolve o limite no dia (SP) do pedido")
    void shouldCancelReservationRejectAndReleaseTheLimitOfTheRequestDay() {
        stubOrder(order(PaymentStatus.SENT_TO_SPI, false));
        firstDelivery();
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        apply(SpiStatus.REJECTED, "AM04");

        verify(ledger).cancel(E2E);
        var saved = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(persistence).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(saved.getValue().rejectReasonCode()).isEqualTo("AM04");
        var released = ArgumentCaptor.forClass(LimitDecision.Allowed.class);
        verify(limits).release(eq(SequentialIds.id(100)), released.capture());
        assertThat(released.getValue().day()).as("dia em America/Sao_Paulo, não em UTC").isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(released.getValue().deviceDailyConsumed()).as("dispositivo NÃO cadastrado ⇒ consumiu o limite do dispositivo").isTrue();
        assertThat(released.getValue().amount()).isEqualByComparingTo(Money.of("80.00"));
        assertThat(publishedEvent().eventType()).isEqualTo("PaymentRejected");
    }

    @Test
    void shouldUseNarrWhenTheRejectionHasNoReasonCode() {
        stubOrder(order(PaymentStatus.SENT_TO_SPI, true));
        firstDelivery();
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        apply(SpiStatus.REJECTED, null);

        var saved = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(persistence).save(saved.capture());
        assertThat(saved.getValue().rejectReasonCode()).isEqualTo("NARR");
    }

    @Test
    void shouldOnlyRecordTheSendWithoutTouchingTheLedger() {
        stubOrder(order(PaymentStatus.RESERVED, true));
        firstDelivery();
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        apply(SpiStatus.SENT, null);

        verifyNoInteractions(ledger, limits);
        assertThat(publishedEvent().eventType()).isEqualTo("PaymentSent");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SETTLED", "REJECTED", "FAILED_TECHNICAL", "CANCELLED"})
    @DisplayName("Ordem já em estado final: não reaplica (sem save, evento nem devolução de limite)")
    void shouldNotReapplyOnAFinalOrder(PaymentStatus finalStatus) {
        stubOrder(order(finalStatus, true));
        firstDelivery();

        apply(SpiStatus.REJECTED, "AM04");

        verify(persistence, never()).save(any());
        verifyNoInteractions(events, limits);
    }

    @Test
    @DisplayName("Mensagem repetida (inbox): nenhum efeito local; o core é idempotente por e2eId")
    void shouldDoNothingLocallyOnAnInboxDuplicate() {
        stubOrder(order(PaymentStatus.SENT_TO_SPI, true));
        when(inbox.firstTime("grp", "msg-1", "pix.spi.payment-status.v1", 0, 3L)).thenReturn(false);

        apply(SpiStatus.SETTLED, null);

        verify(persistence, never()).save(any());
        verifyNoInteractions(events, limits);
    }

    @Test
    void shouldFailWithNotFoundBeforeCallingTheLedgerWhenTheOrderIsUnknown() {
        when(persistence.findByEndToEndId(E2E)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apply(SpiStatus.SETTLED, null)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(ledger, inbox, events, limits);
    }

    // ------------------------------------------------------------------ helpers

    private void apply(SpiStatus status, String reason) {
        service.apply("grp", "msg-1", "pix.spi.payment-status.v1", 0, 3L, E2E, status, reason, AT);
    }

    private void stubOrder(PaymentOrder order) {
        when(persistence.findByEndToEndId(E2E)).thenReturn(Optional.of(order));
    }

    private void firstDelivery() {
        when(inbox.firstTime("grp", "msg-1", "pix.spi.payment-status.v1", 0, 3L)).thenReturn(true);
    }

    /** Ordem no estado pedido (via construtor canônico: o teste não depende da sequência de transições do domínio). */
    private PaymentOrder order(PaymentStatus status, boolean deviceRegistered) {
        return new PaymentOrder(SequentialIds.id(1), SequentialIds.id(100), PaymentMethod.MANUAL, PaymentOrigin.IMMEDIATE,
                new PaymentDestination(null, null, null, "Favorecido", "87654321", "0002", "98765", "CACC"), Money.of("80.00"), null,
                null, new EndToEndId(E2E), status, null, null, UUID.randomUUID(), deviceRegistered, REQUESTED, null, null, 0);
    }

    private PaymentOrderEvent publishedEvent() {
        var captor = ArgumentCaptor.forClass(PaymentOrderEvent.class);
        verify(events).publish(captor.capture());
        return captor.getValue();
    }
}
