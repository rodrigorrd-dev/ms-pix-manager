package br.com.pixmanager.core.application.service.schedule;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.ScheduleRetryPolicy;
import br.com.pixmanager.core.domain.entity.ScheduledOccurrence;
import br.com.pixmanager.core.domain.entity.ScheduledPayment;
import br.com.pixmanager.core.domain.entity.event.ScheduledPaymentEvent;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.PaymentOrderResult;
import br.com.pixmanager.core.port.in.schedule.ExecuteScheduledPaymentsUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.ScheduledPaymentPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.shared.properties.ScheduleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.DoubleSupplier;

/**
 * Worker do Pix Agendado (BR-SCH-02/03/05). Três passos por ciclo: (1) reivindica o que venceu e cria a ordem de pagamento pelo MESMO
 * caminho do pagamento imediato (DICT fora de transação; limite + reserva + ordem + vínculo na mesma transação); (2) acompanha o
 * desfecho das ordens (executada / saldo insuficiente ⇒ retenta 18h–21h e avisa / falha); (3) devolve ao fluxo o que ficou preso.
 * Falha de uma ocorrência nunca interrompe as outras nem cancela as seguintes.
 */
public class ExecuteScheduledPaymentsService implements ExecuteScheduledPaymentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExecuteScheduledPaymentsService.class);

    private final ScheduledPaymentPersistencePortOut schedules;
    private final PaymentOrderPersistencePortOut orders;
    private final CreatePaymentOrderUseCase createOrder;
    private final DomainEventPublisherPortOut events;
    private final TransactionRunnerPortOut transactionRunner;
    private final Clock clock;
    private final ScheduleProperties properties;
    private final ScheduleRetryPolicy retryPolicy;
    private final DoubleSupplier jitter;

    public ExecuteScheduledPaymentsService(ScheduledPaymentPersistencePortOut schedules, PaymentOrderPersistencePortOut orders,
                                           CreatePaymentOrderUseCase createOrder, DomainEventPublisherPortOut events,
                                           TransactionRunnerPortOut transactionRunner, Clock clock, ScheduleProperties properties,
                                           ScheduleRetryPolicy retryPolicy, DoubleSupplier jitter) {
        this.schedules = schedules;
        this.orders = orders;
        this.createOrder = createOrder;
        this.events = events;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.jitter = jitter;
    }

    @Override
    public Result runCycle() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        int[] counters = new int[5];                                                               // started, settled, retried, failed, released
        for (ScheduledOccurrence occurrence : schedules.claimDue(today, now, properties.batch())) {
            guarded(occurrence, () -> start(occurrence, today, now, counters));
        }
        for (ScheduledOccurrence occurrence : schedules.processingWithOrder(properties.batch())) {
            guarded(occurrence, () -> follow(occurrence, now, counters));
        }
        for (ScheduledOccurrence occurrence : schedules.processingWithoutOrderSince(now.minus(properties.stuckAfter()), properties.batch())) {
            guarded(occurrence, () -> {
                transactionRunner.inTransaction(() -> schedules.saveOccurrence(occurrence.technicalRetry(now, "STUCK")));
                counters[4]++;
            });
        }
        return new Result(counters[0], counters[1], counters[2], counters[3], counters[4]);
    }

    // ---------------------------------------------------------------- (1) disparar

    private void start(ScheduledOccurrence occurrence, LocalDate today, Instant now, int[] counters) {
        ScheduledPayment payment = schedules.findById(occurrence.scheduledPaymentId()).orElseThrow();
        if (occurrence.dueDate().isBefore(today.minusDays(properties.maxLateDays()))) {
            fail(payment, occurrence, "MISSED", counters);                                                  // o sistema ficou fora tempo demais: não paga fora de hora
            return;
        }
        CreatePaymentOrderUseCase.PreparedPaymentOrder prepared;
        try {
            prepared = createOrder.prepare(Caller.system("scheduler"), toCommand(payment));      // DICT (HTTP) FORA de transação
        } catch (BusinessException exception) {
            fail(payment, occurrence, exception.getCode(), counters);                                               // chave inexistente, CPF divergente...: não adianta retentar
            return;
        } catch (BadRequestException exception) {
            fail(payment, occurrence, exception.getCode(), counters);
            return;
        } catch (IntegrationException exception) {
            technical(payment, occurrence, now, exception, counters);
            return;
        }
        transactionRunner.inTransaction(() -> {
            PaymentOrderResult result = createOrder.submit(prepared);
            ScheduledOccurrence linked = schedules.saveOccurrence(occurrence.linkedTo(result.orderId()));
            if (result instanceof PaymentOrderResult.Rejected rejected) {                   // limite da data estourado, etc.
                ScheduledOccurrence failed = schedules.saveOccurrence(linked.failed(rejected.errorCode()));
                publish("ScheduledOccurrenceFailed", payment, failed, false);
                counters[3]++;
                closeIfDone(payment);
            } else {
                counters[0]++;
            }
        });
    }

    private void technical(ScheduledPayment payment, ScheduledOccurrence occurrence, Instant now, IntegrationException exception, int[] counters) {
        if (occurrence.attemptCount() >= properties.maxTechnicalAttempts()) {
            fail(payment, occurrence, "TECHNICAL", counters);
            return;
        }
        Instant next = now.plus(properties.technicalBackoff().multipliedBy(Math.max(1, occurrence.attemptCount())));
        transactionRunner.inTransaction(() -> schedules.saveOccurrence(occurrence.technicalRetry(next, exception.getCode())));
        log.warn("Ocorrência {} adiada por falha técnica [{}] até {}", occurrence.id(), exception.getCode(), next);
    }

    // ---------------------------------------------------------------- (2) acompanhar

    private void follow(ScheduledOccurrence occurrence, Instant now, int[] counters) {
        PaymentOrder order = orders.findById(occurrence.paymentOrderId()).orElseThrow();
        ScheduledPayment payment = schedules.findById(occurrence.scheduledPaymentId()).orElseThrow();
        switch (order.status()) {
            case SENT_TO_SPI, SETTLED -> transactionRunner.inTransaction(() -> {
                publish("ScheduledOccurrenceExecuted", payment, schedules.saveOccurrence(occurrence.executed()), false);
                counters[1]++;
                closeIfDone(payment);
            });
            case REJECTED -> {
                if ("AM04".equals(order.rejectReasonCode())) {
                    insufficientFunds(payment, occurrence, now, counters);
                } else {
                    fail(payment, occurrence, order.rejectReasonCode() == null ? "REJECTED" : order.rejectReasonCode(), counters);
                }
            }
            case FAILED_TECHNICAL, CANCELLED -> fail(payment, occurrence, order.status() == br.com.pixmanager.core.domain.enums.PaymentStatus.CANCELLED ? "CANCELLED" : "TECHNICAL", counters);
            default -> { }                                                                  // ainda andando (VALIDATED/RESERVED): volta no próximo ciclo
        }
    }

    /** Saldo insuficiente: nova tentativa no mesmo dia entre 18h e 21h e AVISO ao cliente; sem janela/tentativas ⇒ falha definitiva. */
    private void insufficientFunds(ScheduledPayment payment, ScheduledOccurrence occurrence, Instant now, int[] counters) {
        Instant retryAt = retryPolicy.nextAttempt(occurrence.dueDate(), now, occurrence.attemptCount(), jitter.getAsDouble());
        transactionRunner.inTransaction(() -> {
            ScheduledOccurrence saved = schedules.saveOccurrence(occurrence.insufficientFunds(retryAt, now));
            publish(retryAt == null ? "ScheduledOccurrenceFailed" : "ScheduledOccurrenceRetryNotified", payment, saved, true);
            if (retryAt == null) {
                counters[3]++;
                closeIfDone(payment);
            } else {
                counters[2]++;
            }
        });
    }

    // ---------------------------------------------------------------- apoio

    private void fail(ScheduledPayment payment, ScheduledOccurrence occurrence, String code, int[] counters) {
        transactionRunner.inTransaction(() -> {
            publish("ScheduledOccurrenceFailed", payment, schedules.saveOccurrence(occurrence.failed(code)), false);
            counters[3]++;
            closeIfDone(payment);
        });
    }

    private void closeIfDone(ScheduledPayment payment) {
        if (schedules.countOpen(payment.id()) == 0) {
            ScheduledPayment current = schedules.findById(payment.id()).orElse(payment);
            if (current.status() == br.com.pixmanager.core.domain.enums.ScheduleStatus.ACTIVE) {
                ScheduledPayment done = schedules.save(current.completed());
                events.publish(new ScheduledPaymentEvent("ScheduledPaymentCompleted", done.id(), null, done.payerAccountId(),
                        done.status().name(), null, done.amount(), null, false, null));
            }
        }
    }

    private void publish(String type, ScheduledPayment payment, ScheduledOccurrence occurrence, boolean notify) {
        events.publish(new ScheduledPaymentEvent(type, payment.id(), occurrence.id(), payment.payerAccountId(), occurrence.status().name(), occurrence.dueDate(),
                payment.amount(), occurrence.nextAttemptAt(), notify, occurrence.paymentOrderId()));
    }

    private static CreatePaymentOrderCommand toCommand(ScheduledPayment scheduledPayment) {
        return new CreatePaymentOrderCommand(scheduledPayment.payerAccountId(), scheduledPayment.method(), PaymentOrigin.SCHEDULED, scheduledPayment.amount(), scheduledPayment.pixKeyType(), scheduledPayment.pixKey(),
                null, scheduledPayment.ispb(), scheduledPayment.branch(), scheduledPayment.account(), scheduledPayment.accountType(), scheduledPayment.name(), scheduledPayment.document(), null, scheduledPayment.payerMessage(), null, true);
    }

    /** Uma ocorrência com defeito não pode travar o lote: registra e segue. */
    private void guarded(ScheduledOccurrence occurrence, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.error("Erro ao processar a ocorrência {} do agendamento {}", occurrence.id(), occurrence.scheduledPaymentId(), exception);
        }
    }
}
