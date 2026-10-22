package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.LimitDecision;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.event.PaymentOrderEvent;
import br.com.pixmanager.core.port.in.ledger.ExecuteReservationUseCase;
import br.com.pixmanager.core.port.in.limit.ConsumeLimitUseCase;
import br.com.pixmanager.core.port.in.payment.CancelPaymentOrderUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.model.ExpectedVersion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.UUID;

/** Cancela ANTES do SPI. Valida a transição, cancela no core (HTTP fora de transação, idempotente) e só então persiste. */
public class CancelPaymentOrderService implements CancelPaymentOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelPaymentOrderService.class);

    private final PaymentOrderPersistencePortOut persistence;
    private final ExecuteReservationUseCase ledger;
    private final ConsumeLimitUseCase limits;
    private final DomainEventPublisherPortOut events;
    private final TransactionRunnerPortOut transactionRunner;
    private final ZoneId zone;

    public CancelPaymentOrderService(PaymentOrderPersistencePortOut persistence, ExecuteReservationUseCase ledger, ConsumeLimitUseCase limits,
                                     DomainEventPublisherPortOut events, TransactionRunnerPortOut transactionRunner, ZoneId zone) {
        this.persistence = persistence;
        this.ledger = ledger;
        this.limits = limits;
        this.events = events;
        this.transactionRunner = transactionRunner;
        this.zone = zone;
    }

    @Override
    public PaymentOrder cancel(Caller caller, UUID id, ExpectedVersion expected) {
        PaymentOrder order = persistence.findById(id).filter(candidate -> caller.canAccess(candidate.payerAccountId()))
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Ordem não encontrada."));
        if (!expected.matches(order.version())) {
            throw new PreconditionFailedException("A ordem foi alterada por outra requisição (versão desatualizada).");
        }
        PaymentOrder cancelled = order.cancelled();                         // lança se já foi ao SPI: nenhum I/O antes disso
        ledger.cancel(order.endToEndId().value());                          // core: HTTP fora de transação, idempotente
        PaymentOrder result = transactionRunner.inTransaction(() -> {
            PaymentOrder saved = persistence.save(cancelled);
            limits.release(saved.payerAccountId(), new LimitDecision.Allowed(saved.requestedAt().atZone(zone).toLocalDate(),
                    !saved.deviceRegistered(), saved.amount()));
            events.publish(new PaymentOrderEvent("PaymentCancelled", saved));
            return saved;
        });
        log.info("Ordem de pagamento cancelada: id={} e2eId={}", result.id(), result.endToEndId().value());
        return result;
    }
}
