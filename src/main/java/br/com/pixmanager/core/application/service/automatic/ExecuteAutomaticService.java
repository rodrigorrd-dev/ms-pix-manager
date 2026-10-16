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
    private final EndToEndIdGeneratorPortOut e2eIds;
    private final IdGeneratorPortOut ids;
    private final TransactionRunnerPortOut tx;
    private final Clock clock;
    private final AutomaticProperties properties;

    public ExecuteAutomaticService(AutomaticPersistencePortOut persistence, PaymentOrderPersistencePortOut orders, CreatePaymentOrderUseCase createOrder,
                                   DomainEventPublisherPortOut events, EndToEndIdGeneratorPortOut e2eIds, IdGeneratorPortOut ids,
                                   TransactionRunnerPortOut tx, Clock clock, AutomaticProperties properties) {
        this.persistence = persistence;
        this.orders = orders;
        this.createOrder = createOrder;
        this.events = events;
        this.e2eIds = e2eIds;
        this.ids = ids;
        this.tx = tx;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public Result runCycle() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        int[] c = new int[6];                                                          // expired, started, paid, retried, failed, released
        for (AutomaticAuthorization a : persistence.findExpiredRequests(now, properties.batch())) {
            guarded(a.id(), () -> {
                tx.inTransaction(() -> {
                    AutomaticAuthorization saved = persistence.saveAuthorization(a.expired());
                    events.publish(new AutomaticAuthorizationEvent("AutomaticAuthorizationExpired", saved));
                });
                c[0]++;
            });
        }
        for (AutomaticAttempt attempt : persistence.claimDue(today, now.minusSeconds(60), properties.batch())) {
            guarded(attempt.id(), () -> start(attempt, today, c));
        }
        for (AutomaticAttempt attempt : persistence.inProgressWithOrder(properties.batch())) {
            guarded(attempt.id(), () -> follow(attempt, today, c));
        }
        for (AutomaticAttempt attempt : persistence.inProgressWithoutOrderSince(now.minus(properties.stuckAfter()), properties.batch())) {
            guarded(attempt.id(), () -> {
                tx.inTransaction(() -> persistence.saveAttempt(attempt.with("AGENDADA", "STUCK")));
                c[5]++;
            });
        }
        return new Result(c[0], c[1], c[2], c[3], c[4], c[5]);
    }

    private void start(AutomaticAttempt attempt, LocalDate today, int[] c) {
        AutomaticCharge charge = persistence.findCharge(attempt.chargeId()).orElseThrow();
        AutomaticAuthorization auth = persistence.findAuthorization(charge.authorizationId()).orElseThrow();
        if (!auth.isApproved() || !charge.isLive()) {
            log.error("Débito automático sem autorização aprovada recusado [{}]: cobrança {} autorização {} ({})", ErrorCodes.AUTOMATIC_NOT_AUTHORIZED, charge.id(), auth.id(), auth.status());
            tx.inTransaction(() -> persistence.saveAttempt(attempt.with("CANCELADA", ErrorCodes.AUTOMATIC_NOT_AUTHORIZED)));
            return;
        }
        if (attempt.settlementDate().isBefore(today.minusDays(properties.maxLateDays()))) {
            finish(attempt, charge, "EXPIRADA", "EXPIRADA", "MISSED", c);
            return;
        }
        if (auth.payerMaxAmount() != null && charge.amount().compareTo(auth.payerMaxAmount()) > 0) {
            finish(attempt, charge, "REJEITADA", "REJEITADA", "LIMIT", c);                  // o pagador baixou o teto depois da cobrança
            return;
        }
        CreatePaymentOrderUseCase.PreparedPaymentOrder prepared;
        try {
            prepared = createOrder.prepare(Caller.system("automatic"), new CreatePaymentOrderCommand(auth.payerAccountId(), PaymentMethod.MANUAL,
                    PaymentOrigin.AUTOMATIC, charge.amount(), null, null, null, charge.receiverIspb(), charge.receiverBranch(), charge.receiverAccount(),
                    charge.receiverAccountType(), auth.receiverName(), auth.receiverDocument(), null, auth.contract(), null, true));
        } catch (BusinessException | BadRequestException e) {
            finish(attempt, charge, "REJEITADA", "REJEITADA", e instanceof BusinessException b ? b.getCode() : "BAD_REQUEST", c);
            return;
        } catch (IntegrationException e) {
            tx.inTransaction(() -> persistence.saveAttempt(attempt.with("AGENDADA", e.getCode())));    // técnica: volta e espera, sem queimar tentativa
            log.warn("Tentativa {} adiada por falha técnica [{}]", attempt.id(), e.getCode());
            return;
        }
        tx.inTransaction(() -> {
            PaymentOrderResult result = createOrder.submit(prepared);
            AutomaticAttempt linked = persistence.saveAttempt(attempt.linkedTo(result.orderId(), result.order().endToEndId().value()));
            if (result instanceof PaymentOrderResult.Rejected rejected) {                     // limite da data estourado
                finishRows(linked, persistence.findCharge(charge.id()).orElseThrow(), "REJEITADA", "REJEITADA", rejected.errorCode());
                c[4]++;
            } else {
                c[1]++;
            }
        });
    }

    private void follow(AutomaticAttempt attempt, LocalDate today, int[] c) {
        PaymentOrder order = orders.findById(attempt.paymentOrderId()).orElseThrow();
        AutomaticCharge charge = persistence.findCharge(attempt.chargeId()).orElseThrow();
        switch (order.status()) {
            case SENT_TO_SPI, SETTLED -> {
                tx.inTransaction(() -> finishRows(attempt, charge, "PAGA", "CONCLUIDA", null));
                c[2]++;
            }
            case REJECTED -> {
                AutomaticAuthorization auth = persistence.findAuthorization(charge.authorizationId()).orElseThrow();
                int made = persistence.attemptsOf(charge.id()).size();
                if ("AM04".equals(order.rejectReasonCode()) && auth.allowRetry() && auth.isApproved() && made <= properties.retryDays()) {
                    tx.inTransaction(() -> {
                        persistence.saveAttempt(attempt.with("REJEITADA", "AM04"));
                        persistence.insertAttempt(new AutomaticAttempt(ids.newId(), charge.id(), "NTAG", "AGENDADA", e2eIds.nextEndToEndId(),
                                today.plusDays(1), null, null, 0));
                    });
                    c[3]++;
                } else {
                    finish(attempt, charge, "REJEITADA", "REJEITADA", order.rejectReasonCode() == null ? "REJECTED" : order.rejectReasonCode(), c);
                }
            }
            case FAILED_TECHNICAL, CANCELLED -> finish(attempt, charge, "REJEITADA", "REJEITADA", order.status().name(), c);
            default -> { }
        }
    }

    private void finish(AutomaticAttempt attempt, AutomaticCharge charge, String attemptStatus, String chargeStatus, String error, int[] c) {
        tx.inTransaction(() -> finishRows(attempt, charge, attemptStatus, chargeStatus, error));
        c[4]++;
    }

    private void finishRows(AutomaticAttempt attempt, AutomaticCharge charge, String attemptStatus, String chargeStatus, String error) {
        persistence.saveAttempt(attempt.with(attemptStatus, error));
        persistence.saveCharge(charge.with(chargeStatus));
    }

    private void guarded(java.util.UUID id, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Erro no Pix Automático ao processar {}", id, e);
        }
    }
}
