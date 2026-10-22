package br.com.pixmanager.core.application.service.med;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.entity.MedCase;
import br.com.pixmanager.core.domain.entity.MedContestation;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.core.domain.entity.event.MedCaseEvent;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.enums.MedCaseType;
import br.com.pixmanager.core.domain.enums.MedRole;
import br.com.pixmanager.core.domain.enums.MedStatus;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.domain.enums.RefundNature;
import br.com.pixmanager.core.domain.enums.RefundStatus;
import br.com.pixmanager.core.port.in.ledger.ExecuteReservationUseCase;
import br.com.pixmanager.core.port.in.ledger.RequestReservationUseCase;
import br.com.pixmanager.core.port.in.med.ManageMedCaseUseCase.OpenMedCaseCommand;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase.RequestRefundResult;
import br.com.pixmanager.core.port.out.MedGatewayPortOut;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.MedCasePersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.RefundPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.properties.PixPolicyProperties;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MedCaseService — abertura, aceite com bloqueio, devolução MED, encerramento, contestação e acompanhamento")
class MedCaseServiceTest {

    static final String E2E = "E12345678202610151403abcdefghij0";
    static final TransactionRunnerPortOut TX = new TransactionRunnerPortOut() {
        @Override
        public <T> T inTransaction(Supplier<T> action) {
            return action.get();
        }
    };

    @Mock MedCasePersistencePortOut cases;
    @Mock ReceivedPixPersistencePortOut receivedPix;
    @Mock PaymentOrderPersistencePortOut orders;
    @Mock RefundPersistencePortOut refunds;
    @Mock MedGatewayPortOut gateway;
    @Mock RequestReservationUseCase reservations;
    @Mock ExecuteReservationUseCase ledger;
    @Mock RequestRefundUseCase refundUseCase;
    @Mock DomainEventPublisherPortOut events;

    final UUID accountId = SequentialIds.id(100);
    final Caller caller = Caller.forAccounts("bo", accountId);
    FixedClock clock;
    MedCaseService service;

    @BeforeEach
    void setUp() {
        clock = FixedClock.at("2026-10-15T15:00:00Z");
        service = new MedCaseService(cases, receivedPix, orders, refunds, gateway, reservations, ledger, refundUseCase, events, new SequentialIds(), TX, clock,
                new PixPolicyProperties.Med(Duration.ofDays(80), Duration.ofDays(7), Duration.ofDays(90), Duration.ofDays(80)));
        when(cases.save(any(MedCase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cases.saveContestation(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    ReceivedPix pix(Instant settledAt) {
        return new ReceivedPix(SequentialIds.id(300), new EndToEndId(E2E), null, null, accountId, Money.of("100.00"), Money.of("100.00"), Money.ZERO, null, null, settledAt,
                null, "m", List.of(), 0, settledAt);
    }

    int sequence = 0;

    MedCase medCase(MedRole role, MedStatus status, String blocked, UUID refund) {
        MedCase open = MedCase.open(SequentialIds.id(1000 + ++sequence), role == MedRole.REPORTER ? "FR-1" : null, E2E, SequentialIds.id(300), null, accountId, MedCaseType.FRAUD, role, 1, "golpe",
                Instant.parse("2026-10-15T15:00:00Z"), Instant.parse("2026-10-22T15:00:00Z"), Money.of("100.00"));
        MedCase medCase = new MedCase(open.id(), open.dictFundsRecoveryId(), E2E, open.receivedId(), null, accountId, MedCaseType.FRAUD, role, status, 1, "golpe", open.reportedAt(),
                open.analysisDeadlineAt(), Money.of("100.00"), Money.of(blocked), refund, 0);
        when(cases.findById(medCase.id())).thenReturn(Optional.of(medCase));
        return medCase;
    }

    OpenMedCaseCommand open(MedRole role) {
        return new OpenMedCaseCommand(role, E2E, MedCaseType.FRAUD, "golpe", null);
    }

    String lastEvent() {
        ArgumentCaptor<DomainEvent> domainEventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).publish(domainEventCaptor.capture());
        return ((MedCaseEvent) domainEventCaptor.getAllValues().get(domainEventCaptor.getAllValues().size() - 1)).action();
    }

    @Test
    @DisplayName("abrir REPORTED: valida a janela de 80 dias, duplicidade e escopo; grava caso + histórico + evento")
    void shouldOpenAsReported() {
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(pix(Instant.parse("2026-10-10T10:00:00Z"))));

        MedCase medCase = service.open(caller, open(MedRole.REPORTED));

        assertThat(medCase.status()).isEqualTo(MedStatus.OPEN);
        assertThat(medCase.analysisDeadlineAt()).isEqualTo(Instant.parse("2026-10-22T15:00:00Z"));
        verify(cases).insert(medCase);
        verify(cases).appendHistory(eq(medCase.id()), eq(null), eq(MedStatus.OPEN), eq("bo"), anyString(), any());
        assertThat(lastEvent()).isEqualTo("OPENED");
        verify(gateway, never()).report(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("abrir: fora dos 80 dias ⇒ PIX-422-071; já existe caso ⇒ 409; Pix de outra conta ⇒ 404; campos faltando ⇒ 400")
    void shouldRejectInvalidOpenings() {
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(pix(Instant.parse("2026-07-01T10:00:00Z"))));
        assertThatThrownBy(() -> service.open(caller, open(MedRole.REPORTED))).isInstanceOf(BusinessException.class).hasMessageContaining("80 dias");

        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(pix(Instant.parse("2026-10-10T10:00:00Z"))));
        when(cases.existsLive(E2E, MedRole.REPORTED, 1)).thenReturn(true);
        assertThatThrownBy(() -> service.open(caller, open(MedRole.REPORTED))).isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> service.open(Caller.forAccounts("x", SequentialIds.id(999)), open(MedRole.REPORTED))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.open(caller, new OpenMedCaseCommand(null, E2E, MedCaseType.FRAUD, "x", null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.open(caller, new OpenMedCaseCommand(MedRole.REPORTED, E2E, MedCaseType.FRAUD, "x", 0))).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("abrir REPORTER: só pagamento liquidado; notifica o DICT ANTES de gravar e guarda o id")
    void shouldOpenAsReporter() {
        PaymentOrder order = PaymentOrder.create(SequentialIds.id(70), accountId, PaymentMethod.KEY, PaymentOrigin.IMMEDIATE, null, Money.of("10.00"), null, null,
                new EndToEndId(E2E), true, Instant.parse("2026-10-10T10:00:00Z"));
        when(orders.findByEndToEndId(E2E)).thenReturn(Optional.of(order));
        assertThatThrownBy(() -> service.open(caller, open(MedRole.REPORTER))).isInstanceOf(BusinessException.class).hasMessageContaining("liquidado");

        PaymentOrder settled = order.validated(SequentialIds.id(80)).reserved().sentToSpi(Instant.parse("2026-10-10T10:01:00Z")).settled(Instant.parse("2026-10-10T10:02:00Z"));
        when(orders.findByEndToEndId(E2E)).thenReturn(Optional.of(settled));
        when(gateway.report(eq(E2E), eq(MedCaseType.FRAUD), anyString(), eq(1))).thenReturn("FR-9");

        MedCase medCase = service.open(caller, open(MedRole.REPORTER));

        assertThat(medCase.dictFundsRecoveryId()).isEqualTo("FR-9");
        assertThat(medCase.paymentOrderId()).isEqualTo(SequentialIds.id(70));
    }

    @Test
    @DisplayName("aceitar/recusar/cancelar: só no papel e estado certos; aceitar reserva MED_BLOCK; cancelar ACCEPTED libera o core e o bloqueio")
    void shouldDecide() {
        MedCase medCase = medCase(MedRole.REPORTED, MedStatus.OPEN, "0.00", null);
        assertThat(service.accept(caller, medCase.id(), "ok").status()).isEqualTo(MedStatus.ACCEPTED);
        verify(reservations).request(eq("med-" + medCase.id() + "-1"), eq(accountId), eq(LedgerPurpose.MED_BLOCK), eq("MED_CASE"), eq(medCase.id()), eq(Money.of("100.00")));
        assertThat(service.refuse(caller, medCase.id(), "não procede").status()).isEqualTo(MedStatus.REFUSED);

        MedCase reporter = medCase(MedRole.REPORTER, MedStatus.OPEN, "0.00", null);
        assertThatThrownBy(() -> service.accept(caller, reporter.id(), null)).isInstanceOf(BusinessException.class).hasMessageContaining("REPORTED");
        assertThat(service.cancel(caller, reporter.id(), null).status()).isEqualTo(MedStatus.CANCELLED);
        verify(gateway).cancel("FR-1");

        MedCase accepted = medCase(MedRole.REPORTED, MedStatus.ACCEPTED, "100.00", null);
        MedCase cancelled = service.cancel(caller, accepted.id(), "retirado");
        assertThat(cancelled.blockedAmount()).isEqualTo(Money.ZERO);
        verify(ledger).cancel("med-" + accepted.id() + "-1");
        verify(cases).releaseBlock(accepted.id());
        assertThatThrownBy(() -> service.accept(caller, SequentialIds.id(999), null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("devolver: exige aceito e valor bloqueado; libera o bloqueio no core ANTES, pede a devolução MED_FRAUDE e vira REFUNDING")
    void shouldStartRefund() {
        MedCase noBlock = medCase(MedRole.REPORTED, MedStatus.ACCEPTED, "0.00", null);
        assertThatThrownBy(() -> service.startRefund(caller, noBlock.id())).isInstanceOf(BusinessException.class);

        MedCase medCase = medCase(MedRole.REPORTED, MedStatus.ACCEPTED, "100.00", null);
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(pix(Instant.parse("2026-10-10T10:00:00Z"))));
        Refund refund = Refund.request(SequentialIds.id(400), "med1", SequentialIds.id(300), new EndToEndId(E2E), "D12345678202610151403abcdefghij0", Money.of("100.00"),
                RefundNature.MED_FRAUDE, "MED", Instant.parse("2026-10-15T15:00:00Z"));
        when(refundUseCase.requestMed(any(), any())).thenReturn(new RequestRefundResult.Created(refund));

        MedCase refunding = service.startRefund(caller, medCase.id());

        assertThat(refunding.status()).isEqualTo(MedStatus.REFUNDING);
        assertThat(refunding.refundId()).isEqualTo(refund.id());
        var order = org.mockito.Mockito.inOrder(ledger, refundUseCase, cases);
        order.verify(ledger).cancel("med-" + medCase.id() + "-1");
        order.verify(refundUseCase).requestMed(any(), any());
        order.verify(cases).releaseBlock(medCase.id());
    }

    @Test
    @DisplayName("devolver: valor bloqueado maior que o devolvível ⇒ recusa sem mexer no core")
    void shouldNotReleaseTheBlockWhenTheRefundCannotBeMade() {
        MedCase medCase = medCase(MedRole.REPORTED, MedStatus.ACCEPTED, "100.00", null);
        ReceivedPix consumed = new ReceivedPix(SequentialIds.id(300), new EndToEndId(E2E), null, null, accountId, Money.of("100.00"), Money.of("100.00"), Money.of("60.00"), null, null,
                Instant.parse("2026-10-10T10:00:00Z"), null, "m", List.of(), 0, Instant.parse("2026-10-10T10:00:00Z"));
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> service.startRefund(caller, medCase.id())).isInstanceOf(BusinessException.class);

        verify(ledger, never()).cancel(anyString());
    }

    @Test
    @DisplayName("encerrar: só REPORTER; contestar: só caso CLOSED com devolução e dentro de 80 dias; decidir aceita ⇒ CONTESTED")
    void shouldCloseAndContest() {
        MedCase reporter = medCase(MedRole.REPORTER, MedStatus.OPEN, "0.00", null);
        assertThat(service.close(caller, reporter.id(), "resolvido").status()).isEqualTo(MedStatus.CLOSED);
        MedCase reported = medCase(MedRole.REPORTED, MedStatus.OPEN, "0.00", null);
        assertThatThrownBy(() -> service.close(caller, reported.id(), null)).isInstanceOf(BusinessException.class);

        UUID refundId = SequentialIds.id(400);
        MedCase closed = medCase(MedRole.REPORTED, MedStatus.CLOSED, "100.00", refundId);
        Refund refund = Refund.request(refundId, "med1", SequentialIds.id(300), new EndToEndId(E2E), "D12345678202610151403abcdefghij0", Money.of("100.00"), RefundNature.MED_FRAUDE, "MED",
                Instant.parse("2026-10-01T10:00:00Z")).settled(Instant.parse("2026-10-02T10:00:00Z"));
        when(refunds.findById(refundId)).thenReturn(Optional.of(refund));

        assertThatThrownBy(() -> service.contest(caller, closed.id(), " ")).isInstanceOf(BadRequestException.class);
        MedContestation medContestation = service.contest(caller, closed.id(), "valor legítimo");
        assertThat(medContestation.deadlineAt()).isEqualTo(Instant.parse("2026-12-21T10:00:00Z"));
        assertThat(lastEvent()).isEqualTo("CONTESTATION_OPENED");

        clock.set(Instant.parse("2027-01-15T10:00:00Z"));
        assertThatThrownBy(() -> service.contest(caller, closed.id(), "tarde")).isInstanceOf(BusinessException.class).hasMessageContaining("80 dias");
        MedCase open = medCase(MedRole.REPORTED, MedStatus.OPEN, "0.00", null);
        assertThatThrownBy(() -> service.contest(caller, open.id(), "x")).isInstanceOf(BusinessException.class);

        when(cases.findContestation(medContestation.id())).thenReturn(Optional.of(medContestation));
        assertThat(service.decideContestation(caller, medContestation.id(), true, "procede").status()).isEqualTo("ACCEPTED");
        assertThat(lastEvent()).isEqualTo("CONTESTATION_DECIDED");
        verify(cases).save(org.mockito.ArgumentMatchers.argThat(medCase -> medCase.status() == MedStatus.CONTESTED));
        MedContestation decided = new MedContestation(medContestation.id(), medContestation.caseId(), medContestation.refundId(), "REJECTED", "x", medContestation.refundedAt(), medContestation.deadlineAt(), Instant.now(), 1);
        when(cases.findContestation(medContestation.id())).thenReturn(Optional.of(decided));
        assertThatThrownBy(() -> service.decideContestation(caller, medContestation.id(), true, null)).isInstanceOf(BusinessException.class).hasMessageContaining("já foi decidida");
    }

    @Test
    @DisplayName("acompanhar: devolução DEVOLVIDO fecha o caso; NAO_REALIZADO devolve a ACCEPTED; EM_PROCESSAMENTO não mexe; erro em um caso não derruba os outros")
    void shouldFollowRefunds() {
        UUID refundId = SequentialIds.id(400);
        MedCase refunding = medCase(MedRole.REPORTED, MedStatus.REFUNDING, "100.00", refundId);
        when(cases.findRefunding(50)).thenReturn(List.of(refunding));
        Refund pending = Refund.request(refundId, "med1", SequentialIds.id(300), new EndToEndId(E2E), "D12345678202610151403abcdefghij0", Money.of("100.00"), RefundNature.MED_FRAUDE, "MED",
                Instant.parse("2026-10-15T15:00:00Z"));
        when(refunds.findById(refundId)).thenReturn(Optional.of(pending));
        assertThat(service.runCycle()).isZero();

        when(refunds.findById(refundId)).thenReturn(Optional.of(pending.settled(Instant.parse("2026-10-15T15:05:00Z"))));
        assertThat(service.runCycle()).isEqualTo(1);
        assertThat(lastEvent()).isEqualTo("REFUND_SETTLED");

        when(refunds.findById(refundId)).thenReturn(Optional.of(pending.failed("sem saldo")));
        assertThat(service.runCycle()).isEqualTo(1);
        assertThat(lastEvent()).isEqualTo("REFUND_FAILED");
        assertThat(RefundStatus.NAO_REALIZADO).isNotNull();

        when(refunds.findById(refundId)).thenReturn(Optional.empty());
        assertThat(service.runCycle()).as("defeito num caso é registrado e o ciclo segue").isZero();
    }

    @Test
    @DisplayName("bloqueio MED: reservado grava o valor; caso já cancelado não grava; recusado registra o motivo")
    void shouldHandleBlockOutcomes() {
        MedBlockReservationHandler handler = new MedBlockReservationHandler(cases, events, clock);
        MedCase accepted = medCase(MedRole.REPORTED, MedStatus.ACCEPTED, "0.00", null);
        LedgerReservation reservation = LedgerReservation.pending(SequentialIds.id(900), "med-" + accepted.id() + "-1", accountId, LedgerPurpose.MED_BLOCK, "MED_CASE", accepted.id(),
                Money.of("100.00"), clock.instant());

        handler.onReserved(reservation);
        verify(cases).insertBlock(accepted.id(), accountId, Money.of("100.00").value(), reservation.id());
        assertThat(lastEvent()).isEqualTo("BLOCK_APPLIED");

        org.mockito.Mockito.clearInvocations(cases);
        MedCase cancelled = medCase(MedRole.REPORTED, MedStatus.CANCELLED, "0.00", null);
        LedgerReservation late = LedgerReservation.pending(SequentialIds.id(901), "k", accountId, LedgerPurpose.MED_BLOCK, "MED_CASE", cancelled.id(), Money.of("100.00"), clock.instant());
        handler.onReserved(late);
        verify(cases, never()).insertBlock(any(), any(), any(), any());

        handler.onRefused(reservation);
        verify(cases).appendHistory(eq(accepted.id()), eq(MedStatus.ACCEPTED), eq(MedStatus.ACCEPTED), eq("system"), org.mockito.ArgumentMatchers.contains("recusado"), any());
        assertThat(lastEvent()).isEqualTo("BLOCK_REFUSED");
        assertThat(handler.purpose()).isEqualTo(LedgerPurpose.MED_BLOCK);
    }
}
