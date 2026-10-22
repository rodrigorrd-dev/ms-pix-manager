package br.com.pixmanager.core.application.service.automatic;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.AutomaticAttempt;
import br.com.pixmanager.core.domain.entity.AutomaticAuthorization;
import br.com.pixmanager.core.domain.entity.AutomaticCharge;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.enums.AccountStatus;
import br.com.pixmanager.core.domain.enums.AutomaticPeriodicity;
import br.com.pixmanager.core.domain.enums.HolderType;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.automatic.ManageAutomaticUseCase.ReceiveAuthorizationCommand;
import br.com.pixmanager.core.port.in.automatic.ManageAutomaticUseCase.RegisterChargeCommand;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.PaymentOrderResult;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.PreparedPaymentOrder;
import br.com.pixmanager.core.port.out.AutomaticGatewayPortOut;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.generator.EndToEndIdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.AutomaticPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.DependencyUnavailableException;
import br.com.pixmanager.shared.exception.InvalidTransitionException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.shared.properties.AutomaticProperties;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Pix Automático (pagador) — autorização, cobrança e worker de execução")
class AutomaticServicesTest {

    static final String E2E = "E12345678202610151403abcdefghij0";
    static final TransactionRunnerPortOut TX = new TransactionRunnerPortOut() {
        @Override
        public <T> T inTransaction(Supplier<T> action) {
            return action.get();
        }
    };

    @Mock AutomaticPersistencePortOut persistence;
    @Mock PaymentOrderPersistencePortOut orders;
    @Mock FindAccountUseCase accounts;
    @Mock AutomaticGatewayPortOut gateway;
    @Mock CreatePaymentOrderUseCase createOrder;
    @Mock DomainEventPublisherPortOut events;
    @Mock EndToEndIdGeneratorPortOut endToEndIdGenerator;

    final UUID payerId = SequentialIds.id(100);
    final Caller caller = Caller.forAccounts("psp", payerId);
    final AutomaticProperties automaticProperties = new AutomaticProperties(null, null, null, null, null, null, null, null);
    FixedClock clock;
    ManageAutomaticService manage;
    ExecuteAutomaticService worker;

    @BeforeEach
    void setUp() {
        clock = FixedClock.at("2026-10-15T15:00:00Z");
        manage = new ManageAutomaticService(persistence, accounts, gateway, events, new SequentialIds(), endToEndIdGenerator, TX, clock, automaticProperties);
        worker = new ExecuteAutomaticService(persistence, orders, createOrder, events, endToEndIdGenerator, new SequentialIds(), TX, clock, automaticProperties);
        when(endToEndIdGenerator.nextEndToEndId()).thenReturn(E2E);
        when(accounts.findById(payerId)).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));
        when(persistence.saveAuthorization(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveCharge(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveAttempt(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    AccountInfo account(AccountStatus status) {
        return new AccountInfo(payerId, SequentialIds.id(200), HolderType.INDIVIDUAL, Cpf.of("11144477735"), "h", "Pagador", "12345678", "0001", "1", "CACC", status);
    }

    ReceiveAuthorizationCommand receive(String idRec, String payerDoc) {
        return new ReceiveAuthorizationCommand(idRec, payerId, payerDoc, "11222333000181", "Academia", "CONTRATO", null, AutomaticPeriodicity.MENSAL,
                LocalDate.parse("2026-10-01"), null, Money.of("50.00"), null);
    }

    AutomaticAuthorization authorization(String status, String idRec) {
        AutomaticAuthorization received = AutomaticAuthorization.received(SequentialIds.id(1), idRec, payerId, "11222333000181", "Academia", "CONTRATO", null, AutomaticPeriodicity.MENSAL,
                LocalDate.parse("2026-10-01"), null, Money.of("50.00"), null, Instant.parse("2026-10-22T15:00:00Z"));
        AutomaticAuthorization authorization = "APROVADA".equals(status) ? received.approved(null, clock.instant()) : received;
        when(persistence.findAuthorization(authorization.id())).thenReturn(Optional.of(authorization));
        return authorization;
    }

    static final String REC_R = "RR" + "a".repeat(27);
    static final String REC_N = "RN" + "b".repeat(27);

    RegisterChargeCommand charge(String due, String amount) {
        return new RegisterChargeCommand("t".repeat(30), LocalDate.parse(due), Money.of(amount), "12345678", "0001", "998877", "CACC");
    }

    // ------------------------------------------------------------------ domínio

    @Test
    @DisplayName("idRec: formato validado e allow_retry derivado do 2º caractere; datas e valores inconsistentes são recusados")
    void shouldValidateTheAuthorization() {
        assertThat(authorization("CRIADA", REC_R).allowRetry()).isTrue();
        assertThat(authorization("CRIADA", REC_N).allowRetry()).isFalse();
        for (String bad : List.of("X", "RR123", "XR" + "a".repeat(27), "RX" + "a".repeat(27))) {
            assertThatThrownBy(() -> AutomaticAuthorization.received(UUID.randomUUID(), bad, payerId, "1", "A", "C", null, AutomaticPeriodicity.MENSAL, LocalDate.now(), null, null, null,
                    Instant.now())).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> AutomaticAuthorization.received(UUID.randomUUID(), REC_R, payerId, "1", "A", "C", null, AutomaticPeriodicity.MENSAL, LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-01"), null, null, Instant.now())).hasMessageContaining("final");
    }

    @Test
    @DisplayName("transições: aprovar/rejeitar só pendente; teto não pode ficar abaixo do mínimo/valor fixo; expirada não aprova; cancelar só pendente/aprovada")
    void shouldGuardTransitions() {
        AutomaticAuthorization pending = authorization("CRIADA", REC_R);
        assertThatThrownBy(() -> pending.approved(Money.of("10.00"), clock.instant())).isInstanceOf(BusinessException.class);          // abaixo do valor fixo (50)
        assertThatThrownBy(() -> pending.approved(null, Instant.parse("2026-11-30T00:00:00Z"))).hasMessageContaining("expirou");
        assertThatThrownBy(() -> pending.rejected("AP99")).isInstanceOf(BusinessException.class);
        assertThat(pending.rejected("AP14").rejectionCode()).isEqualTo("AP14");
        assertThat(pending.expired().status()).isEqualTo("EXPIRADA");
        AutomaticAuthorization approved = pending.approved(Money.of("80.00"), clock.instant());
        assertThatThrownBy(() -> approved.approved(null, clock.instant())).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> approved.rejected("AP13")).isInstanceOf(InvalidTransitionException.class);
        assertThat(approved.withPayerMax(Money.of("60.00")).payerMaxAmount()).isEqualTo(Money.of("60.00"));
        assertThatThrownBy(() -> pending.withPayerMax(Money.of("60.00"))).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> approved.withPayerMax(Money.of("40.00"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> approved.cancelled("ALIEN", clock.instant())).isInstanceOf(BusinessException.class);
        AutomaticAuthorization cancelled = approved.cancelled("PAYER", clock.instant());
        assertThatThrownBy(() -> cancelled.cancelled("PAYER", clock.instant())).isInstanceOf(InvalidTransitionException.class);
        assertThat(cancelled.isApproved()).isFalse();
    }

    // ------------------------------------------------------------------ gestão

    @Test
    @DisplayName("receber: documento do pagador tem que ser o do titular; conta alheia ⇒ 404; inativa ⇒ 422; ok grava e publica")
    void shouldReceiveOnlyForTheHolder() {
        assertThat(manage.receiveAuthorization(caller, receive(REC_R, "111.444.777-35")).status()).isEqualTo("CRIADA");
        verify(persistence).insertAuthorization(any());
        verify(events).publish(any());
        assertThatThrownBy(() -> manage.receiveAuthorization(caller, receive(REC_R, "52998224725"))).isInstanceOf(BusinessException.class).hasMessageContaining("titular");
        assertThatThrownBy(() -> manage.receiveAuthorization(Caller.forAccounts("x", SequentialIds.id(999)), receive(REC_R, "11144477735"))).isInstanceOf(NotFoundException.class);
        when(accounts.findById(payerId)).thenReturn(Optional.of(account(AccountStatus.BLOCKED)));
        assertThatThrownBy(() -> manage.receiveAuthorization(caller, receive(REC_R, "11144477735"))).isInstanceOf(BusinessException.class).hasMessageContaining("ativa");
    }

    @Test
    @DisplayName("aprovar/rejeitar/cancelar: gravam, publicam e respondem ao recebedor; cancelar pelo RECEBEDOR não devolve aviso; cancelar cancela cobranças vivas")
    void shouldDecideAndNotifyTheReceiver() {
        AutomaticAuthorization pending = authorization("CRIADA", REC_R);
        assertThat(manage.approve(caller, pending.id(), Money.of("80.00")).status()).isEqualTo("APROVADA");
        verify(gateway).approve(eq(REC_R), eq(Money.of("80.00")));
        assertThat(manage.reject(caller, pending.id(), "AP13").status()).isEqualTo("REJEITADA");
        verify(gateway).reject(REC_R, "AP13");

        AutomaticAuthorization approved = authorization("APROVADA", REC_R);
        AutomaticCharge live = new AutomaticCharge(SequentialIds.id(5), approved.id(), "t".repeat(30), LocalDate.parse("2026-10-18"), Money.of("50.00"), "12345678", "0001", "9", "CACC", "ATIVA", 0);
        when(persistence.chargesOf(approved.id())).thenReturn(List.of(live));
        when(persistence.attemptsOf(live.id())).thenReturn(List.of(new AutomaticAttempt(SequentialIds.id(6), live.id(), "AGND", "AGENDADA", E2E, live.dueDate(), null, null, 0)));
        assertThat(manage.cancel(caller, approved.id(), "RECEIVER").status()).isEqualTo("CANCELADA");
        verify(gateway, never()).cancel(any(), eq("RECEIVER"));
        verify(persistence).saveCharge(org.mockito.ArgumentMatchers.argThat(charge -> charge.status().equals("CANCELADA")));
        manage.cancel(caller, approved.id(), "PAYER");
        verify(gateway).cancel(REC_R, "PAYER");
        assertThat(manage.adjustLimit(caller, approved.id(), Money.of("60.00")).payerMaxAmount()).isEqualTo(Money.of("60.00"));
        assertThatThrownBy(() -> manage.getAuthorization(Caller.forAccounts("x", SequentialIds.id(999)), approved.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("cobrança: exige aprovação (PIX-422-060), txid válido, antecedência 2–10 dias, vigência, valor fixo e teto; ok cria a tentativa AGND")
    void shouldValidateCharges() {
        AutomaticAuthorization pending = authorization("CRIADA", REC_R);
        assertThatThrownBy(() -> manage.registerCharge(caller, pending.id(), charge("2026-10-18", "50.00"))).isInstanceOf(BusinessException.class).hasMessageContaining("não está aprovada");

        AutomaticAuthorization authorization = authorization("APROVADA", REC_R);
        assertThatThrownBy(() -> manage.registerCharge(caller, authorization.id(), new RegisterChargeCommand("curto", LocalDate.parse("2026-10-18"), Money.of("50.00"), "12345678", "0001", "9", "CACC"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> manage.registerCharge(caller, authorization.id(), new RegisterChargeCommand("t".repeat(30), LocalDate.parse("2026-10-18"), Money.of("50.00"), "123", "0001", "9", "CACC"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> manage.registerCharge(caller, authorization.id(), charge("2026-10-16", "50.00"))).hasMessageContaining("dias");
        assertThatThrownBy(() -> manage.registerCharge(caller, authorization.id(), charge("2026-11-30", "50.00"))).hasMessageContaining("dias");
        assertThatThrownBy(() -> manage.registerCharge(caller, authorization.id(), charge("2026-10-18", "51.00"))).hasMessageContaining("valor");

        AutomaticCharge ok = manage.registerCharge(caller, authorization.id(), charge("2026-10-18", "50.00"));
        assertThat(ok.status()).isEqualTo("ATIVA");
        ArgumentCaptor<AutomaticAttempt> first = ArgumentCaptor.forClass(AutomaticAttempt.class);
        verify(persistence).insertCharge(eq(ok), first.capture());
        assertThat(first.getValue().type()).isEqualTo("AGND");
        assertThat(first.getValue().settlementDate()).isEqualTo(LocalDate.parse("2026-10-18"));
    }

    @Test
    @DisplayName("cancelar cobrança: ativa e antes da véspera; viva demais, vencida ou já cancelada ⇒ 422; listagens respeitam o escopo")
    void shouldCancelChargesUntilTheEve() {
        AutomaticAuthorization authorization = authorization("APROVADA", REC_R);
        AutomaticCharge live = new AutomaticCharge(SequentialIds.id(5), authorization.id(), "t".repeat(30), LocalDate.parse("2026-10-18"), Money.of("50.00"), "12345678", "0001", "9", "CACC", "ATIVA", 0);
        when(persistence.findCharge(live.id())).thenReturn(Optional.of(live));
        when(persistence.attemptsOf(live.id())).thenReturn(List.of());

        assertThat(manage.cancelCharge(caller, authorization.id(), live.id()).status()).isEqualTo("CANCELADA");
        assertThat(manage.attempts(caller, authorization.id(), live.id())).isEmpty();
        assertThat(manage.charges(caller, authorization.id())).isEmpty();

        clock.set(Instant.parse("2026-10-18T03:30:00Z"));                                      // 00:30 do dia do vencimento (Brasília)
        assertThatThrownBy(() -> manage.cancelCharge(caller, authorization.id(), live.id())).hasMessageContaining("23h59");
        when(persistence.findCharge(live.id())).thenReturn(Optional.of(live.with("CONCLUIDA")));
        assertThatThrownBy(() -> manage.cancelCharge(caller, authorization.id(), live.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> manage.cancelCharge(caller, authorization.id(), SequentialIds.id(404))).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ worker

    AutomaticCharge activeCharge(UUID authId) {
        AutomaticCharge automaticCharge = new AutomaticCharge(SequentialIds.id(5), authId, "t".repeat(30), LocalDate.parse("2026-10-18"), Money.of("50.00"), "12345678", "0001", "9", "CACC", "ATIVA", 0);
        when(persistence.findCharge(automaticCharge.id())).thenReturn(Optional.of(automaticCharge));
        return automaticCharge;
    }

    AutomaticAttempt claimed(AutomaticCharge automaticCharge, String type, LocalDate day) {
        AutomaticAttempt automaticAttempt = new AutomaticAttempt(SequentialIds.id(6), automaticCharge.id(), type, "SOLICITADA", E2E, day, null, null, 1);
        when(persistence.claimDue(any(), any(), anyInt())).thenReturn(List.of(automaticAttempt));
        return automaticAttempt;
    }

    PaymentOrder order() {
        return PaymentOrder.create(SequentialIds.id(70), payerId, PaymentMethod.MANUAL, PaymentOrigin.AUTOMATIC, null, Money.of("50.00"), null, null, new EndToEndId(E2E), true, clock.instant());
    }

    String lastAttempt() {
        ArgumentCaptor<AutomaticAttempt> automaticAttemptCaptor = ArgumentCaptor.forClass(AutomaticAttempt.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce()).saveAttempt(automaticAttemptCaptor.capture());
        AutomaticAttempt automaticAttempt = automaticAttemptCaptor.getAllValues().get(automaticAttemptCaptor.getAllValues().size() - 1);
        return automaticAttempt.status() + (automaticAttempt.lastErrorCode() == null ? "" : ":" + automaticAttempt.lastErrorCode());
    }

    @Test
    @DisplayName("worker: autorização cancelada ⇒ tentativa CANCELADA (PIX-422-060), atraso ⇒ EXPIRADA, teto reduzido ⇒ REJEITADA:LIMIT; nada é pago")
    void shouldRefuseToDebitWithoutAuthorization() {
        AutomaticAuthorization authorization = authorization("APROVADA", REC_R);
        AutomaticAuthorization cancelled = authorization.cancelled("PAYER", clock.instant());
        when(persistence.findAuthorization(authorization.id())).thenReturn(Optional.of(cancelled));
        claimed(activeCharge(authorization.id()), "AGND", LocalDate.parse("2026-10-15"));
        worker.runCycle();
        assertThat(lastAttempt()).isEqualTo("CANCELADA:PIX-422-060");

        org.mockito.Mockito.reset(persistence);
        when(persistence.saveAttempt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveCharge(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findAuthorization(authorization.id())).thenReturn(Optional.of(authorization));
        claimed(activeCharge(authorization.id()), "AGND", LocalDate.parse("2026-10-01"));
        worker.runCycle();
        assertThat(lastAttempt()).isEqualTo("EXPIRADA:MISSED");

        org.mockito.Mockito.reset(persistence);
        when(persistence.saveAttempt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveCharge(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findAuthorization(authorization.id())).thenReturn(Optional.of(authorization.withPayerMax(Money.of("50.00"))));
        AutomaticCharge big = new AutomaticCharge(SequentialIds.id(5), authorization.id(), "t".repeat(30), LocalDate.parse("2026-10-18"), Money.of("60.00"), "12345678", "0001", "9", "CACC", "ATIVA", 0);
        when(persistence.findCharge(big.id())).thenReturn(Optional.of(big));
        claimed(big, "AGND", LocalDate.parse("2026-10-15"));
        worker.runCycle();
        assertThat(lastAttempt()).isEqualTo("REJEITADA:LIMIT");
        verify(createOrder, never()).prepare(any(), any());
    }

    @Test
    @DisplayName("worker: negócio ⇒ REJEITADA; técnica ⇒ volta AGENDADA; ordem criada vincula e conta; limite estourado ⇒ REJEITADA")
    void shouldStartAttempts() {
        AutomaticAuthorization authorization = authorization("APROVADA", REC_R);
        AutomaticCharge automaticCharge = activeCharge(authorization.id());
        claimed(automaticCharge, "AGND", LocalDate.parse("2026-10-15"));
        when(createOrder.prepare(any(), any())).thenThrow(new BusinessException("PIX-422-012", "x"));
        assertThat(worker.runCycle().failed()).isEqualTo(1);
        assertThat(lastAttempt()).isEqualTo("REJEITADA:PIX-422-012");

        org.mockito.Mockito.reset(persistence);
        when(persistence.saveAttempt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findAuthorization(authorization.id())).thenReturn(Optional.of(authorization));
        activeCharge(authorization.id());
        claimed(automaticCharge, "AGND", LocalDate.parse("2026-10-15"));
        org.mockito.Mockito.reset(createOrder);
        when(createOrder.prepare(any(), any())).thenThrow(new DependencyUnavailableException("dict", null));
        worker.runCycle();
        assertThat(lastAttempt()).isEqualTo("AGENDADA:" + new DependencyUnavailableException("x", null).getCode());

        org.mockito.Mockito.reset(persistence);
        when(persistence.saveAttempt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveCharge(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findAuthorization(authorization.id())).thenReturn(Optional.of(authorization));
        activeCharge(authorization.id());
        claimed(automaticCharge, "AGND", LocalDate.parse("2026-10-15"));
        PaymentOrder created = order();
        org.mockito.Mockito.reset(createOrder);
        when(createOrder.prepare(any(), any())).thenReturn(new PreparedPaymentOrder(created));
        when(createOrder.submit(any())).thenReturn(new PaymentOrderResult.Created(created));
        assertThat(worker.runCycle().started()).isEqualTo(1);
        assertThat(lastAttempt()).isEqualTo("SOLICITADA");

        when(createOrder.submit(any())).thenReturn(new PaymentOrderResult.Rejected(created, "PIX-422-050", "limite"));
        assertThat(worker.runCycle().failed()).isEqualTo(1);
    }

    AutomaticAttempt inProgress(AutomaticAuthorization authorization, int attemptsMade) {
        AutomaticCharge automaticCharge = activeCharge(authorization.id());
        AutomaticAttempt automaticAttempt = new AutomaticAttempt(SequentialIds.id(6), automaticCharge.id(), "AGND", "SOLICITADA", E2E, LocalDate.parse("2026-10-18"), SequentialIds.id(70), null, 1);
        when(persistence.inProgressWithOrder(anyInt())).thenReturn(List.of(automaticAttempt));
        when(persistence.attemptsOf(automaticCharge.id())).thenReturn(java.util.Collections.nCopies(attemptsMade, automaticAttempt));
        return automaticAttempt;
    }

    @Test
    @DisplayName("acompanhar: enviada ⇒ PAGA/CONCLUIDA; AM04 com allow_retry ⇒ nova tentativa NTAG no dia seguinte; sem allow_retry, sem tentativas restantes ou outro código ⇒ REJEITADA")
    void shouldFollowOutcomes() {
        clock.set(Instant.parse("2026-10-18T15:00:00Z"));
        AutomaticAuthorization authorization = authorization("APROVADA", REC_R);
        inProgress(authorization, 1);
        when(orders.findById(SequentialIds.id(70))).thenReturn(Optional.of(order().validated(SequentialIds.id(80)).reserved().sentToSpi(clock.instant())));
        assertThat(worker.runCycle().paid()).isEqualTo(1);
        verify(persistence).saveCharge(org.mockito.ArgumentMatchers.argThat(charge -> charge.status().equals("CONCLUIDA")));

        org.mockito.Mockito.reset(persistence);
        when(persistence.saveAttempt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveCharge(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findAuthorization(authorization.id())).thenReturn(Optional.of(authorization));
        inProgress(authorization, 1);
        PaymentOrder noFunds = order().validated(SequentialIds.id(80)).reserved().rejected("AM04", "sem saldo");
        when(orders.findById(SequentialIds.id(70))).thenReturn(Optional.of(noFunds));
        assertThat(worker.runCycle().retried()).isEqualTo(1);
        ArgumentCaptor<AutomaticAttempt> next = ArgumentCaptor.forClass(AutomaticAttempt.class);
        verify(persistence).insertAttempt(next.capture());
        assertThat(next.getValue().type()).isEqualTo("NTAG");
        assertThat(next.getValue().settlementDate()).isEqualTo(LocalDate.parse("2026-10-19"));

        org.mockito.Mockito.reset(persistence);
        when(persistence.saveAttempt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveCharge(any())).thenAnswer(invocation -> invocation.getArgument(0));
        inProgress(authorization, 4);                                                                           // 1 + 3 tentativas: acabaram
        when(persistence.findAuthorization(authorization.id())).thenReturn(Optional.of(authorization));
        worker.runCycle();
        verify(persistence).saveCharge(org.mockito.ArgumentMatchers.argThat(charge -> charge.status().equals("REJEITADA")));

        AutomaticAuthorization noRetry = authorization("APROVADA", REC_N);
        org.mockito.Mockito.reset(persistence);
        when(persistence.saveAttempt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveCharge(any())).thenAnswer(invocation -> invocation.getArgument(0));
        inProgress(noRetry, 1);
        worker.runCycle();
        verify(persistence, never()).insertAttempt(any());

        org.mockito.Mockito.reset(persistence);
        when(persistence.saveAttempt(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.saveCharge(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(persistence.findAuthorization(authorization.id())).thenReturn(Optional.of(authorization));
        inProgress(authorization, 1);
        when(orders.findById(SequentialIds.id(70))).thenReturn(Optional.of(order().validated(SequentialIds.id(80)).reserved().rejected("AC03", "conta")));
        worker.runCycle();
        assertThat(lastAttempt()).isEqualTo("REJEITADA:AC03");
    }

    @Test
    @DisplayName("ciclo: solicitação vencida expira; tentativa sem ordem presa volta a AGENDADA; erro isolado não derruba o ciclo")
    void shouldExpireReleaseAndSurvive() {
        AutomaticAuthorization pending = authorization("CRIADA", REC_R);
        when(persistence.findExpiredRequests(any(), anyInt())).thenReturn(List.of(pending));
        assertThat(worker.runCycle().expired()).isEqualTo(1);
        verify(persistence).saveAuthorization(org.mockito.ArgumentMatchers.argThat(authorization -> authorization.status().equals("EXPIRADA")));

        AutomaticAttempt stuck = new AutomaticAttempt(SequentialIds.id(6), SequentialIds.id(5), "AGND", "SOLICITADA", E2E, LocalDate.parse("2026-10-15"), null, null, 1);
        when(persistence.inProgressWithoutOrderSince(any(), anyInt())).thenReturn(List.of(stuck));
        assertThat(worker.runCycle().released()).isEqualTo(1);
        assertThat(lastAttempt()).isEqualTo("AGENDADA:STUCK");

        when(persistence.claimDue(any(), any(), anyInt())).thenReturn(List.of(stuck));
        when(persistence.findCharge(any())).thenReturn(Optional.empty());                         // defeito
        assertThat(worker.runCycle().started()).isZero();
    }
}
