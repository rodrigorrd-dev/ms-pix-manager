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
    private final TransactionRunnerPortOut tx;
    private final Clock clock;
    private final ScheduleProperties properties;
    private final ScheduleRetryPolicy retryPolicy;
    private final DoubleSupplier jitter;

    public ExecuteScheduledPaymentsService(ScheduledPaymentPersistencePortOut schedules, PaymentOrderPersistencePortOut orders,
                                           CreatePaymentOrderUseCase createOrder, DomainEventPublisherPortOut events,
                                           TransactionRunnerPortOut tx, Clock clock, ScheduleProperties properties,
                                           ScheduleRetryPolicy retryPolicy, DoubleSupplier jitter) {
        this.schedules = schedules;
        this.orders = orders;
        this.createOrder = createOrder;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.jitter = jitter;
    }

    @Override
    public Result runCycle() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        int[] c = new int[5];                                                               // started, settled, retried, failed, released
        for (ScheduledOccurrence o : schedules.claimDue(today, now, properties.batch())) {
            guarded(o, () -> start(o, today, now, c));
        }
        for (ScheduledOccurrence o : schedules.processingWithOrder(properties.batch())) {
            guarded(o, () -> follow(o, now, c));
        }
        for (ScheduledOccurrence o : schedules.processingWithoutOrderSince(now.minus(properties.stuckAfter()), properties.batch())) {
            guarded(o, () -> {
                tx.inTransaction(() -> schedules.saveOccurrence(o.technicalRetry(now, "STUCK")));
                c[4]++;
            });
        }
        return new Result(c[0], c[1], c[2], c[3], c[4]);
    }

    // ---------------------------------------------------------------- (1) disparar

    private void start(ScheduledOccurrence o, LocalDate today, Instant now, int[] c) {
        ScheduledPayment payment = schedules.findById(o.scheduledPaymentId()).orElseThrow();
        if (o.dueDate().isBefore(today.minusDays(properties.maxLateDays()))) {
            fail(payment, o, "MISSED", c);                                                  // o sistema ficou fora tempo demais: não paga fora de hora
            return;
        }
        CreatePaymentOrderUseCase.PreparedPaymentOrder prepared;
        try {
            prepared = createOrder.prepare(Caller.system("scheduler"), toCommand(payment));      // DICT (HTTP) FORA de transação
        } catch (BusinessException e) {
            fail(payment, o, e.getCode(), c);                                               // chave inexistente, CPF divergente...: não adianta retentar
            return;
        } catch (BadRequestException e) {
            fail(payment, o, e.getCode(), c);
            return;
        } catch (IntegrationException e) {
            technical(payment, o, now, e, c);
            return;
        }
        tx.inTransaction(() -> {
            PaymentOrderResult result = createOrder.submit(prepared);
            ScheduledOccurrence linked = schedules.saveOccurrence(o.linkedTo(result.orderId()));
            if (result instanceof PaymentOrderResult.Rejected rejected) {                   // limite da data estourado, etc.
                ScheduledOccurrence failed = schedules.saveOccurrence(linked.failed(rejected.errorCode()));
                publish("ScheduledOccurrenceFailed", payment, failed, false);
                c[3]++;
                closeIfDone(payment);
            } else {
                c[0]++;
            }
        });
    }

    private void technical(ScheduledPayment payment, ScheduledOccurrence o, Instant now, IntegrationException e, int[] c) {
        if (o.attemptCount() >= properties.maxTechnicalAttempts()) {
            fail(payment, o, "TECHNICAL", c);
            return;
        }
        Instant next = now.plus(properties.technicalBackoff().multipliedBy(Math.max(1, o.attemptCount())));
        tx.inTransaction(() -> schedules.saveOccurrence(o.technicalRetry(next, e.getCode())));
        log.warn("Ocorrência {} adiada por falha técnica [{}] até {}", o.id(), e.getCode(), next);
    }

    // ---------------------------------------------------------------- (2) acompanhar

    private void follow(ScheduledOccurrence o, Instant now, int[] c) {
        PaymentOrder order = orders.findById(o.paymentOrderId()).orElseThrow();
        ScheduledPayment payment = schedules.findById(o.scheduledPaymentId()).orElseThrow();
        switch (order.status()) {
            case SENT_TO_SPI, SETTLED -> tx.inTransaction(() -> {
                publish("ScheduledOccurrenceExecuted", payment, schedules.saveOccurrence(o.executed()), false);
                c[1]++;
                closeIfDone(payment);
            });
            case REJECTED -> {
                if ("AM04".equals(order.rejectReasonCode())) {
                    insufficientFunds(payment, o, now, c);
                } else {
                    fail(payment, o, order.rejectReasonCode() == null ? "REJECTED" : order.rejectReasonCode(), c);
                }
            }
            case FAILED_TECHNICAL, CANCELLED -> fail(payment, o, order.status() == br.com.pixmanager.core.domain.enums.PaymentStatus.CANCELLED ? "CANCELLED" : "TECHNICAL", c);
            default -> { }                                                                  // ainda andando (VALIDATED/RESERVED): volta no próximo ciclo
        }
    }

    /** Saldo insuficiente: nova tentativa no mesmo dia entre 18h e 21h e AVISO ao cliente; sem janela/tentativas ⇒ falha definitiva. */
    private void insufficientFunds(ScheduledPayment payment, ScheduledOccurrence o, Instant now, int[] c) {
        Instant retryAt = retryPolicy.nextAttempt(o.dueDate(), now, o.attemptCount(), jitter.getAsDouble());
        tx.inTransaction(() -> {
            ScheduledOccurrence saved = schedules.saveOccurrence(o.insufficientFunds(retryAt, now));
            publish(retryAt == null ? "ScheduledOccurrenceFailed" : "ScheduledOccurrenceRetryNotified", payment, saved, true);
            if (retryAt == null) {
                c[3]++;
                closeIfDone(payment);
            } else {
                c[2]++;
            }
        });
    }

    // ---------------------------------------------------------------- apoio

    private void fail(ScheduledPayment payment, ScheduledOccurrence o, String code, int[] c) {
        tx.inTransaction(() -> {
            publish("ScheduledOccurrenceFailed", payment, schedules.saveOccurrence(o.failed(code)), false);
            c[3]++;
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

    private void publish(String type, ScheduledPayment payment, ScheduledOccurrence o, boolean notify) {
        events.publish(new ScheduledPaymentEvent(type, payment.id(), o.id(), payment.payerAccountId(), o.status().name(), o.dueDate(),
                payment.amount(), o.nextAttemptAt(), notify, o.paymentOrderId()));
    }

    private static CreatePaymentOrderCommand toCommand(ScheduledPayment p) {
        return new CreatePaymentOrderCommand(p.payerAccountId(), p.method(), PaymentOrigin.SCHEDULED, p.amount(), p.pixKeyType(), p.pixKey(),
                null, p.ispb(), p.branch(), p.account(), p.accountType(), p.name(), p.document(), null, p.payerMessage(), null, true);
    }

    /** Uma ocorrência com defeito não pode travar o lote: registra e segue. */
    private void guarded(ScheduledOccurrence o, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Erro ao processar a ocorrência {} do agendamento {}", o.id(), o.scheduledPaymentId(), e);
        }
    }
}
