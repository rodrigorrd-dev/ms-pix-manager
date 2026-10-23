package br.com.pixmanager.core.application.service.automatic;

import br.com.pixmanager.core.domain.entity.AutomaticAttempt;
import br.com.pixmanager.core.domain.entity.AutomaticAuthorization;
import br.com.pixmanager.core.domain.entity.AutomaticCharge;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.event.AutomaticAuthorizationEvent;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.port.in.automatic.ExecuteAutomaticUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.PaymentOrderResult;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.generator.EndToEndIdGeneratorPortOut;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.AutomaticPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.shared.properties.AutomaticProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Worker do Pix Automático (BR-AUT-05..08). Mesmo desenho do Pix Agendado: reivindica a tentativa do dia (SKIP LOCKED), resolve o DICT/limites
 * pelo MESMO caminho do pagamento imediato (origin AUTOMATIC) e acompanha o desfecho da ordem. Saldo insuficiente com {@code allow_retry}
 * gera nova tentativa (NTAG) no dia seguinte, até {@code retryDays}; sem {@code allow_retry} a cobrança é REJEITADA.
 */
public class ExecuteAutomaticService implements ExecuteAutomaticUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExecuteAutomaticService.class);

    private final AutomaticPersistencePortOut persistence;
    private final PaymentOrderPersistencePortOut orders;
    private final CreatePaymentOrderUseCase createOrder;
    private final DomainEventPublisherPortOut events;
    private final EndToEndIdGeneratorPortOut endToEndIdGenerator;
    private final IdGeneratorPortOut ids;
    private final TransactionRunnerPortOut transactionRunner;
    private final Clock clock;
    private final AutomaticProperties properties;

    public ExecuteAutomaticService(AutomaticPersistencePortOut persistence, PaymentOrderPersistencePortOut orders, CreatePaymentOrderUseCase createOrder,
                                   DomainEventPublisherPortOut events, EndToEndIdGeneratorPortOut endToEndIdGenerator, IdGeneratorPortOut ids,
                                   TransactionRunnerPortOut transactionRunner, Clock clock, AutomaticProperties properties) {
        this.persistence = persistence;
        this.orders = orders;
        this.createOrder = createOrder;
        this.events = events;
        this.endToEndIdGenerator = endToEndIdGenerator;
        this.ids = ids;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public Result runCycle() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        CycleCounters counters = new CycleCounters();
        for (AutomaticAuthorization authorization : persistence.findExpiredRequests(now, properties.batch())) {
            guarded(authorization.id(), () -> {
                transactionRunner.inTransaction(() -> {
                    AutomaticAuthorization saved = persistence.saveAuthorization(authorization.expired());
                    events.publish(new AutomaticAuthorizationEvent("AutomaticAuthorizationExpired", saved));
                });
                counters.expired();
            });
        }
        for (AutomaticAttempt attempt : persistence.claimDue(today, now.minusSeconds(60), properties.batch())) {
            guarded(attempt.id(), () -> start(attempt, today, counters));
        }
        for (AutomaticAttempt attempt : persistence.inProgressWithOrder(properties.batch())) {
            guarded(attempt.id(), () -> follow(attempt, today, counters));
        }
        for (AutomaticAttempt attempt : persistence.inProgressWithoutOrderSince(now.minus(properties.stuckAfter()), properties.batch())) {
            guarded(attempt.id(), () -> {
                transactionRunner.inTransaction(() -> persistence.saveAttempt(attempt.with("AGENDADA", "STUCK")));
                counters.released();
            });
        }
        return counters.toResult();
    }

    private void start(AutomaticAttempt attempt, LocalDate today, CycleCounters counters) {
        AutomaticCharge charge = persistence.findCharge(attempt.chargeId()).orElseThrow();
        AutomaticAuthorization authorization = persistence.findAuthorization(charge.authorizationId()).orElseThrow();
        if (!authorization.isApproved() || !charge.isLive()) {
            log.error("Débito automático sem autorização aprovada recusado [{}]: cobrança {} autorização {} ({})", ErrorCodes.AUTOMATIC_NOT_AUTHORIZED, charge.id(), authorization.id(), authorization.status());
            transactionRunner.inTransaction(() -> persistence.saveAttempt(attempt.with("CANCELADA", ErrorCodes.AUTOMATIC_NOT_AUTHORIZED)));
            return;
        }
        if (attempt.settlementDate().isBefore(today.minusDays(properties.maxLateDays()))) {
            finish(attempt, charge, "EXPIRADA", "EXPIRADA", "MISSED", counters);
            return;
        }
        if (authorization.payerMaxAmount() != null && charge.amount().compareTo(authorization.payerMaxAmount()) > 0) {
            finish(attempt, charge, "REJEITADA", "REJEITADA", "LIMIT", counters);                  // o pagador baixou o teto depois da cobrança
            return;
        }
        CreatePaymentOrderUseCase.PreparedPaymentOrder prepared;
        try {
            prepared = createOrder.prepare(Caller.system("automatic"), new CreatePaymentOrderCommand(authorization.payerAccountId(), PaymentMethod.MANUAL,
                    PaymentOrigin.AUTOMATIC, charge.amount(), null, null, null, charge.receiverIspb(), charge.receiverBranch(), charge.receiverAccount(),
                    charge.receiverAccountType(), authorization.receiverName(), authorization.receiverDocument(), null, authorization.contract(), null, true));
        } catch (BusinessException | BadRequestException exception) {
            finish(attempt, charge, "REJEITADA", "REJEITADA", exception instanceof BusinessException b ? b.getCode() : "BAD_REQUEST", counters);
            return;
        } catch (IntegrationException exception) {
            transactionRunner.inTransaction(() -> persistence.saveAttempt(attempt.with("AGENDADA", exception.getCode())));    // técnica: volta e espera, sem queimar tentativa
            log.warn("Tentativa {} adiada por falha técnica [{}]", attempt.id(), exception.getCode());
            return;
        }
        transactionRunner.inTransaction(() -> {
            PaymentOrderResult result = createOrder.submit(prepared);
            AutomaticAttempt linked = persistence.saveAttempt(attempt.linkedTo(result.orderId(), result.order().endToEndId().value()));
            if (result instanceof PaymentOrderResult.Rejected rejected) {                     // limite da data estourado
                finishRows(linked, persistence.findCharge(charge.id()).orElseThrow(), "REJEITADA", "REJEITADA", rejected.errorCode());
                counters.failed();
            } else {
                counters.started();
            }
        });
    }

    private void follow(AutomaticAttempt attempt, LocalDate today, CycleCounters counters) {
        PaymentOrder order = orders.findById(attempt.paymentOrderId()).orElseThrow();
        AutomaticCharge charge = persistence.findCharge(attempt.chargeId()).orElseThrow();
        switch (order.status()) {
            case SENT_TO_SPI, SETTLED -> {
                transactionRunner.inTransaction(() -> finishRows(attempt, charge, "PAGA", "CONCLUIDA", null));
                counters.paid();
            }
            case REJECTED -> {
                AutomaticAuthorization authorization = persistence.findAuthorization(charge.authorizationId()).orElseThrow();
                int made = persistence.attemptsOf(charge.id()).size();
                if ("AM04".equals(order.rejectReasonCode()) && authorization.allowRetry() && authorization.isApproved() && made <= properties.retryDays()) {
                    transactionRunner.inTransaction(() -> {
                        persistence.saveAttempt(attempt.with("REJEITADA", "AM04"));
                        persistence.insertAttempt(new AutomaticAttempt(ids.newId(), charge.id(), "NTAG", "AGENDADA", endToEndIdGenerator.nextEndToEndId(),
                                today.plusDays(1), null, null, 0));
                    });
                    counters.retried();
                } else {
                    finish(attempt, charge, "REJEITADA", "REJEITADA", order.rejectReasonCode() == null ? "REJECTED" : order.rejectReasonCode(), counters);
                }
            }
            case FAILED_TECHNICAL, CANCELLED -> finish(attempt, charge, "REJEITADA", "REJEITADA", order.status().name(), counters);
            default -> { }
        }
    }

    private void finish(AutomaticAttempt attempt, AutomaticCharge charge, String attemptStatus, String chargeStatus, String error, CycleCounters counters) {
        transactionRunner.inTransaction(() -> finishRows(attempt, charge, attemptStatus, chargeStatus, error));
        counters.failed();
    }

    private void finishRows(AutomaticAttempt attempt, AutomaticCharge charge, String attemptStatus, String chargeStatus, String error) {
        persistence.saveAttempt(attempt.with(attemptStatus, error));
        persistence.saveCharge(charge.with(chargeStatus));
    }

    private void guarded(java.util.UUID id, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.error("Erro no Pix Automático ao processar {}", id, exception);
        }
    }

    /** Acumulador do ciclo: cada contador tem nome, em vez de um índice mágico num {@code int[]}. */
    private static final class CycleCounters {
        private int expired, started, paid, retried, failed, released;
        void expired() { expired++; }
        void started() { started++; }
        void paid() { paid++; }
        void retried() { retried++; }
        void failed() { failed++; }
        void released() { released++; }

        Result toResult() {
            return new Result(expired, started, paid, retried, failed, released);
        }
    }
}
