package br.com.pixmanager.core.application.service.schedule;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.ScheduleRetryPolicy;
import br.com.pixmanager.core.domain.entity.ScheduledOccurrence;
import br.com.pixmanager.core.domain.entity.ScheduledPayment;
import br.com.pixmanager.core.domain.entity.event.DomainEvent;
import br.com.pixmanager.core.domain.entity.event.ScheduledPaymentEvent;
import br.com.pixmanager.core.domain.enums.AccountStatus;
import br.com.pixmanager.core.domain.enums.HolderType;
import br.com.pixmanager.core.domain.enums.OccurrenceStatus;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.enums.Recurrence;
import br.com.pixmanager.core.domain.enums.ScheduleStatus;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.PaymentOrderResult;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.PreparedPaymentOrder;
import br.com.pixmanager.core.port.in.schedule.ManageScheduledPaymentUseCase.CreateScheduledPaymentCommand;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.ScheduledPaymentPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.DependencyUnavailableException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.model.ExpectedVersion;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.shared.properties.ScheduleProperties;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Pix Agendado — gestão (criar/cancelar) e worker (disparar, acompanhar, retentar, liberar)")
class ScheduleServicesTest {

    static final Instant NOON = Instant.parse("2026-10-15T15:00:00Z");           // 12:00 em Brasília
    static final TransactionRunnerPortOut TX = new TransactionRunnerPortOut() {
        @Override
        public <T> T inTransaction(Supplier<T> action) {
            return action.get();
        }
    };

    @Mock ScheduledPaymentPersistencePortOut schedules;
    @Mock PaymentOrderPersistencePortOut orders;
    @Mock FindAccountUseCase accounts;
    @Mock CreatePaymentOrderUseCase createOrder;
    @Mock DomainEventPublisherPortOut events;

    final UUID payerId = SequentialIds.id(100);
    final Caller caller = Caller.forAccounts("cli", payerId);
    final ScheduleProperties props = new ScheduleProperties(null, null, null, null, null, null, null);
    FixedClock clock;
    ManageScheduledPaymentService manage;
    ExecuteScheduledPaymentsService worker;

    @BeforeEach
    void setUp() {
        clock = FixedClock.at("2026-10-15T15:00:00Z");
        manage = new ManageScheduledPaymentService(schedules, accounts, events, new SequentialIds(), clock, props);
        worker = new ExecuteScheduledPaymentsService(schedules, orders, createOrder, events, TX, clock, props, ScheduleRetryPolicy.standard(props.zone()), () -> 0.5);
        when(accounts.findById(payerId)).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));
        when(schedules.save(any(ScheduledPayment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(schedules.saveOccurrence(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    AccountInfo account(AccountStatus status) {
        return new AccountInfo(payerId, SequentialIds.id(200), HolderType.INDIVIDUAL, Cpf.of("11144477735"), "h", "Pagador", "12345678", "0001", "1", "CACC", status);
    }

    CreateScheduledPaymentCommand cmd(Recurrence recurrence, LocalDate first, Integer total) {
        return new CreateScheduledPaymentCommand(payerId, recurrence, PaymentMethod.KEY, PixKeyType.EMAIL, "alguem@exemplo.com.br", null, null, null, null, null, null,
                Money.of("10.00"), null, first, null, total);
    }

    ScheduledPayment payment(ScheduleStatus status) {
        return new ScheduledPayment(SequentialIds.id(1), payerId, Recurrence.ONCE, PaymentMethod.KEY, PixKeyType.EMAIL, "alguem@exemplo.com.br", null, null, null, null, null,
                null, Money.of("10.00"), null, LocalDate.parse("2026-10-16"), null, null, status, true, 0);
    }

    ScheduledOccurrence occurrence(OccurrenceStatus status, int attempts, UUID order) {
        return new ScheduledOccurrence(SequentialIds.id(50), SequentialIds.id(1), 1, LocalDate.parse("2026-10-15"), status, attempts, null, null, order, null, 3);
    }

    // ------------------------------------------------------------------ gestão

    @Test
    @DisplayName("create: grava agendamento + 1 ocorrência por data e publica ScheduledPaymentCreated")
    void shouldCreateWithOccurrences() {
        ScheduledPayment created = manage.create(caller, cmd(Recurrence.WEEKLY, LocalDate.parse("2026-10-20"), 3));

        assertThat(created.status()).isEqualTo(ScheduleStatus.ACTIVE);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<ScheduledOccurrence>> occ = ArgumentCaptor.forClass(List.class);
        verify(schedules).insert(eq(created), occ.capture());
        assertThat(occ.getValue()).extracting(ScheduledOccurrence::dueDate).containsExactly(LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-27"), LocalDate.parse("2026-11-03"));
        ArgumentCaptor<DomainEvent> domainEventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue().eventType()).isEqualTo("ScheduledPaymentCreated");
    }

    @Test
    @DisplayName("create: conta alheia ⇒ 404; inativa, horizonte estourado e chave incompatível com o tipo ⇒ 422")
    void shouldRejectInvalidCreations() {
        assertThatThrownBy(() -> manage.create(Caller.forAccounts("x", SequentialIds.id(999)), cmd(Recurrence.ONCE, LocalDate.parse("2026-10-20"), null))).isInstanceOf(NotFoundException.class);
        when(accounts.findById(payerId)).thenReturn(Optional.of(account(AccountStatus.BLOCKED)));
        assertThatThrownBy(() -> manage.create(caller, cmd(Recurrence.ONCE, LocalDate.parse("2026-10-20"), null))).isInstanceOf(BusinessException.class).hasMessageContaining("ativa");
        when(accounts.findById(payerId)).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));
        assertThatThrownBy(() -> manage.create(caller, cmd(Recurrence.ONCE, LocalDate.parse("2029-10-20"), null))).isInstanceOf(BusinessException.class).hasMessageContaining("horizonte");
        var badKey = new CreateScheduledPaymentCommand(payerId, Recurrence.ONCE, PaymentMethod.KEY, PixKeyType.CPF, "alguem@exemplo.com.br", null, null, null, null, null, null,
                Money.of("10.00"), null, LocalDate.parse("2026-10-20"), null, null);
        assertThatThrownBy(() -> manage.create(caller, badKey)).isInstanceOf(BusinessException.class).hasMessageContaining("chave");
        verify(schedules, never()).insert(any(), any());
    }

    @Test
    @DisplayName("get/list/occurrences respeitam o escopo da conta (404 para quem não pode)")
    void shouldHideOthersSchedules() {
        when(schedules.findById(SequentialIds.id(1))).thenReturn(Optional.of(payment(ScheduleStatus.ACTIVE)));
        Caller other = Caller.forAccounts("x", SequentialIds.id(999));

        assertThat(manage.get(caller, SequentialIds.id(1)).id()).isEqualTo(SequentialIds.id(1));
        assertThatThrownBy(() -> manage.get(other, SequentialIds.id(1))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> manage.occurrences(other, SequentialIds.id(1))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> manage.list(other, payerId, br.com.pixmanager.shared.model.dto.PageQuery.of(0, 10))).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("cancel: versão errada ⇒ 412; ok cancela o agendamento e só as ocorrências canceláveis")
    void shouldCancelOnlyCancellableOccurrences() {
        when(schedules.findById(SequentialIds.id(1))).thenReturn(Optional.of(payment(ScheduleStatus.ACTIVE)));
        when(schedules.occurrencesOf(SequentialIds.id(1))).thenReturn(List.of(occurrence(OccurrenceStatus.SCHEDULED, 0, null), occurrence(OccurrenceStatus.EXECUTED, 1, null)));

        assertThatThrownBy(() -> manage.cancel(caller, SequentialIds.id(1), new ExpectedVersion.Exact(9))).isInstanceOf(PreconditionFailedException.class);
        ScheduledPayment cancelled = manage.cancel(caller, SequentialIds.id(1), new ExpectedVersion.Exact(0));

        assertThat(cancelled.status()).isEqualTo(ScheduleStatus.CANCELLED);
        verify(schedules, org.mockito.Mockito.times(1)).saveOccurrence(any());
    }

    @Test
    @DisplayName("cancelOccurrence: ocorrência de outro agendamento ⇒ 404; última em aberto cancelada conclui o agendamento")
    void shouldCompleteWhenNothingIsOpen() {
        when(schedules.findById(SequentialIds.id(1))).thenReturn(Optional.of(payment(ScheduleStatus.ACTIVE)));
        when(schedules.findOccurrence(SequentialIds.id(50))).thenReturn(Optional.of(occurrence(OccurrenceStatus.SCHEDULED, 0, null)));
        when(schedules.findOccurrence(SequentialIds.id(51))).thenReturn(Optional.empty());
        when(schedules.countOpen(SequentialIds.id(1))).thenReturn(0L);

        assertThatThrownBy(() -> manage.cancelOccurrence(caller, SequentialIds.id(1), SequentialIds.id(51))).isInstanceOf(NotFoundException.class);
        ScheduledOccurrence done = manage.cancelOccurrence(caller, SequentialIds.id(1), SequentialIds.id(50));

        assertThat(done.status()).isEqualTo(OccurrenceStatus.CANCELLED);
        ArgumentCaptor<ScheduledPayment> saved = ArgumentCaptor.forClass(ScheduledPayment.class);
        verify(schedules).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(ScheduleStatus.COMPLETED);
    }

    // ------------------------------------------------------------------ worker

    void claim(ScheduledOccurrence occurrence) {
        when(schedules.claimDue(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(occurrence));
        when(schedules.findById(SequentialIds.id(1))).thenReturn(Optional.of(payment(ScheduleStatus.ACTIVE)));
        when(schedules.countOpen(any())).thenReturn(1L);
    }

    PaymentOrder order(String e2e) {
        return PaymentOrder.create(SequentialIds.id(70), payerId, PaymentMethod.KEY, PaymentOrigin.SCHEDULED, null, Money.of("10.00"), null, null,
                new br.com.pixmanager.core.domain.entity.EndToEndId(e2e), true, NOON);
    }

    String lastStatus() {
        ArgumentCaptor<ScheduledOccurrence> scheduledOccurrenceCaptor = ArgumentCaptor.forClass(ScheduledOccurrence.class);
        verify(schedules, org.mockito.Mockito.atLeastOnce()).saveOccurrence(scheduledOccurrenceCaptor.capture());
        ScheduledOccurrence last = scheduledOccurrenceCaptor.getAllValues().get(scheduledOccurrenceCaptor.getAllValues().size() - 1);
        return last.status() + (last.lastErrorCode() == null ? "" : ":" + last.lastErrorCode());
    }

    @Test
    @DisplayName("worker: falha de negócio no DICT ⇒ FAILED com o código, sem ordem")
    void shouldFailOnBusinessErrors() {
        claim(occurrence(OccurrenceStatus.PROCESSING, 1, null));
        when(createOrder.prepare(any(), any())).thenThrow(new BusinessException("PIX-422-012", "Chave não encontrada"));
        when(schedules.countOpen(any())).thenReturn(0L);

        var r = worker.runCycle();

        assertThat(r.failed()).isEqualTo(1);
        assertThat(lastStatus()).isEqualTo("FAILED:PIX-422-012");
        verify(createOrder, never()).submit(any());
    }

    @Test
    @DisplayName("worker: falha técnica volta a aguardar (sem queimar tentativa); esgotadas as tentativas técnicas ⇒ FAILED:TECHNICAL")
    void shouldRetryTechnicalThenFail() {
        claim(occurrence(OccurrenceStatus.PROCESSING, 1, null));
        when(createOrder.prepare(any(), any())).thenThrow(new DependencyUnavailableException("dict fora", null));

        worker.runCycle();
        assertThat(lastStatus()).startsWith("SCHEDULED");

        org.mockito.Mockito.reset(schedules);
        when(schedules.saveOccurrence(any())).thenAnswer(invocation -> invocation.getArgument(0));
        claim(occurrence(OccurrenceStatus.PROCESSING, 5, null));
        worker.runCycle();
        assertThat(lastStatus()).isEqualTo("FAILED:TECHNICAL");
    }

    @Test
    @DisplayName("worker: parado além de max-late-days ⇒ FAILED:MISSED sem tocar no DICT")
    void shouldNotPayWhenTooLate() {
        claim(new ScheduledOccurrence(SequentialIds.id(50), SequentialIds.id(1), 1, LocalDate.parse("2026-10-01"), OccurrenceStatus.PROCESSING, 1, null, null, null, null, 3));

        worker.runCycle();

        assertThat(lastStatus()).isEqualTo("FAILED:MISSED");
        verify(createOrder, never()).prepare(any(), any());
    }

    @Test
    @DisplayName("worker: ordem criada vincula a ocorrência; ordem recusada por limite ⇒ FAILED com o código")
    void shouldLinkTheOrderOrFailOnLimit() {
        claim(occurrence(OccurrenceStatus.PROCESSING, 1, null));
        PaymentOrder created = order("E12345678202610151403abcdefghij0");
        when(createOrder.prepare(any(), any())).thenReturn(new PreparedPaymentOrder(created));
        when(createOrder.submit(any())).thenReturn(new PaymentOrderResult.Created(created));

        assertThat(worker.runCycle().started()).isEqualTo(1);
        assertThat(lastStatus()).isEqualTo("PROCESSING");

        org.mockito.Mockito.reset(schedules);
        when(schedules.saveOccurrence(any())).thenAnswer(invocation -> invocation.getArgument(0));
        claim(occurrence(OccurrenceStatus.PROCESSING, 1, null));
        when(createOrder.submit(any())).thenReturn(new PaymentOrderResult.Rejected(created, "PIX-422-050", "limite"));
        assertThat(worker.runCycle().failed()).isEqualTo(1);
        assertThat(lastStatus()).isEqualTo("FAILED:PIX-422-050");
    }

    PaymentOrder progressed(String how) {
        PaymentOrder paymentOrder = order("E12345678202610151403abcdefghij0").validated(SequentialIds.id(80)).reserved();
        return switch (how) {
            case "SENT" -> paymentOrder.sentToSpi(NOON);
            case "AM04" -> paymentOrder.rejected("AM04", "sem saldo");
            case "AC03" -> paymentOrder.rejected("AC03", "conta inválida");
            default -> paymentOrder;
        };
    }

    ScheduledOccurrence followed(int attempts) {
        ScheduledOccurrence occurrence = occurrence(OccurrenceStatus.PROCESSING, attempts, SequentialIds.id(70));
        when(schedules.processingWithOrder(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(occurrence));
        when(schedules.findById(SequentialIds.id(1))).thenReturn(Optional.of(payment(ScheduleStatus.ACTIVE)));
        when(schedules.countOpen(any())).thenReturn(0L);
        return occurrence;
    }

    @Test
    @DisplayName("acompanhar: SENT_TO_SPI ⇒ EXECUTED e agendamento concluído")
    void shouldMarkExecuted() {
        followed(1);
        when(orders.findById(SequentialIds.id(70))).thenReturn(Optional.of(progressed("SENT")));

        assertThat(worker.runCycle().settled()).isEqualTo(1);

        assertThat(lastStatus()).isEqualTo("EXECUTED");
        ArgumentCaptor<ScheduledPayment> done = ArgumentCaptor.forClass(ScheduledPayment.class);
        verify(schedules).save(done.capture());
        assertThat(done.getValue().status()).isEqualTo(ScheduleStatus.COMPLETED);
    }

    @Test
    @DisplayName("acompanhar: AM04 na 1ª tentativa ⇒ RETRY_PENDING dentro da janela 18h–21h, avisando o cliente; na 2ª ⇒ FAILED_INSUFFICIENT_FUNDS")
    void shouldRetryInTheWindowThenGiveUp() {
        followed(1);
        when(orders.findById(SequentialIds.id(70))).thenReturn(Optional.of(progressed("AM04")));
        when(schedules.countOpen(any())).thenReturn(1L);

        assertThat(worker.runCycle().retried()).isEqualTo(1);

        ArgumentCaptor<ScheduledOccurrence> retry = ArgumentCaptor.forClass(ScheduledOccurrence.class);
        verify(schedules).saveOccurrence(retry.capture());
        assertThat(retry.getValue().status()).isEqualTo(OccurrenceStatus.RETRY_PENDING);
        assertThat(retry.getValue().customerNotifiedAt()).isNotNull();
        assertThat(retry.getValue().nextAttemptAt()).isBetween(Instant.parse("2026-10-15T21:00:00Z"), Instant.parse("2026-10-16T00:00:00Z"));
        ArgumentCaptor<DomainEvent> domainEventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(domainEventCaptor.capture());
        assertThat(domainEventCaptor.getValue()).isInstanceOfSatisfying(ScheduledPaymentEvent.class, event -> {
            assertThat(event.eventType()).isEqualTo("ScheduledOccurrenceRetryNotified");
            assertThat(event.customerNotificationRequired()).isTrue();
        });

        org.mockito.Mockito.reset(schedules);
        when(schedules.saveOccurrence(any())).thenAnswer(invocation -> invocation.getArgument(0));
        followed(2);
        worker.runCycle();
        assertThat(lastStatus()).isEqualTo("FAILED_INSUFFICIENT_FUNDS:AM04");
    }

    @Test
    @DisplayName("acompanhar: rejeição com outro código ⇒ FAILED:código; ordem cancelada ⇒ FAILED:CANCELLED; ordem ainda andando ⇒ nada")
    void shouldFailOnOtherOutcomes() {
        followed(1);
        when(orders.findById(SequentialIds.id(70))).thenReturn(Optional.of(progressed("AC03")));
        worker.runCycle();
        assertThat(lastStatus()).isEqualTo("FAILED:AC03");

        org.mockito.Mockito.reset(schedules);
        when(schedules.saveOccurrence(any())).thenAnswer(invocation -> invocation.getArgument(0));
        followed(1);
        when(orders.findById(SequentialIds.id(70))).thenReturn(Optional.of(order("E12345678202610151403abcdefghij0").cancelled()));
        worker.runCycle();
        assertThat(lastStatus()).isEqualTo("FAILED:CANCELLED");

        org.mockito.Mockito.reset(schedules);
        followed(1);
        when(orders.findById(SequentialIds.id(70))).thenReturn(Optional.of(progressed("RESERVED")));
        worker.runCycle();
        verify(schedules, never()).saveOccurrence(any());
    }

    @Test
    @DisplayName("preso: PROCESSING sem ordem há mais que stuck-after volta ao fluxo; erro numa ocorrência não derruba o ciclo")
    void shouldReleaseStuckAndSurviveBadOccurrences() {
        when(schedules.processingWithoutOrderSince(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(occurrence(OccurrenceStatus.PROCESSING, 1, null)));

        assertThat(worker.runCycle().released()).isEqualTo(1);
        assertThat(lastStatus()).isEqualTo("SCHEDULED:STUCK");

        when(schedules.claimDue(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(occurrence(OccurrenceStatus.PROCESSING, 1, null)));
        when(schedules.findById(SequentialIds.id(1))).thenReturn(Optional.empty());              // defeito: agendamento sumiu
        assertThat(worker.runCycle().started()).isZero();                                          // registra e segue
    }
}
