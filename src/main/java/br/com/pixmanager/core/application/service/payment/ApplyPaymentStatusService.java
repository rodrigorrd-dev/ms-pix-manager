package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.LimitDecision;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.event.PaymentOrderEvent;
import br.com.pixmanager.core.port.in.ledger.ExecuteReservationUseCase;
import br.com.pixmanager.core.port.in.limit.ConsumeLimitUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.event.InboxPortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;

import java.time.Instant;
import java.time.ZoneId;

/**
 * pacs.002. ORDEM deliberada (sem dual-write, igual à devolução): (1) core {@code confirmar|cancelar} — HTTP FORA de transação,
 * idempotente por {@code endToEndId}; (2) uma transação com inbox + ordem + devolução do limite (se rejeitada) + evento.
 */
public class ApplyPaymentStatusService implements ApplyPaymentStatusUseCase {

    private final PaymentOrderPersistencePortOut persistence;
    private final ExecuteReservationUseCase ledger;
    private final ConsumeLimitUseCase limits;
    private final DomainEventPublisherPortOut events;
    private final InboxPortOut inbox;
    private final TransactionRunnerPortOut transactionRunner;
    private final ZoneId zone;

    public ApplyPaymentStatusService(PaymentOrderPersistencePortOut persistence, ExecuteReservationUseCase ledger,
                                     ConsumeLimitUseCase limits, DomainEventPublisherPortOut events, InboxPortOut inbox,
                                     TransactionRunnerPortOut transactionRunner, ZoneId zone) {
        this.persistence = persistence;
        this.ledger = ledger;
        this.limits = limits;
        this.events = events;
        this.inbox = inbox;
        this.transactionRunner = transactionRunner;
        this.zone = zone;
    }

    @Override
    public void apply(String consumerGroup, String messageId, String topic, int partition, long offset, String endToEndId,
                      SpiStatus status, String reasonCode, Instant at) {
        PaymentOrder known = persistence.findByEndToEndId(endToEndId)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Ordem não encontrada para o endToEndId."));
        switch (status) {
            case SETTLED -> ledger.confirm(endToEndId);
            case REJECTED -> ledger.cancel(endToEndId);
            case SENT -> { /* só registra o envio */ }
        }
        transactionRunner.inTransaction(() -> {
            if (!inbox.firstTime(consumerGroup, messageId, topic, partition, offset)) {
                return;
            }
            PaymentOrder current = persistence.findByEndToEndId(endToEndId).orElse(known);
            if (current.status().isFinal()) {
                return;                                              // já decidida: não reaplica
            }
            switch (status) {
                case SENT -> {
                    PaymentOrder sent = persistence.save(current.sentToSpi(at));
                    events.publish(new PaymentOrderEvent("PaymentSent", sent));
                }
                case SETTLED -> {
                    PaymentOrder settled = persistence.save(current.settled(at));
                    events.publish(new PaymentOrderEvent("PaymentSettled", settled));
                }
                case REJECTED -> {
                    PaymentOrder rejected = persistence.save(current.rejected(reasonCode == null ? "NARR" : reasonCode, "Rejeitado no SPI"));
                    limits.release(rejected.payerAccountId(), new LimitDecision.Allowed(rejected.requestedAt().atZone(zone).toLocalDate(),
                            !rejected.deviceRegistered(), rejected.amount()));
                    events.publish(new PaymentOrderEvent("PaymentRejected", rejected));
                }
            }
        });
    }
}
