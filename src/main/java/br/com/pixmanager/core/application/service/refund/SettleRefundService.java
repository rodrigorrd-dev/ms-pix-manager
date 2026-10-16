package br.com.pixmanager.core.application.service.refund;

import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.domain.entity.event.RefundEvent;
import br.com.pixmanager.core.port.in.ledger.ExecuteReservationUseCase;
import br.com.pixmanager.core.port.in.refund.SettleRefundUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.event.InboxPortOut;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.RefundPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;

import java.time.Instant;

/**
 * Resultado do pacs.004. ORDEM deliberada (sem dual-write): (1) core {@code confirmar|cancelar} — HTTP, FORA de transação e
 * idempotente por {@code rtrId}; (2) uma transação com inbox + devolução + evento. Se (2) falhar, a reentrega repete (1)
 * sem efeito e tenta (2) de novo.
 */
public class SettleRefundService implements SettleRefundUseCase {

    private final RefundPersistencePortOut refunds;
    private final ReceivedPixPersistencePortOut receivedPix;
    private final ExecuteReservationUseCase ledger;
    private final DomainEventPublisherPortOut events;
    private final InboxPortOut inbox;
    private final TransactionRunnerPortOut tx;

    public SettleRefundService(RefundPersistencePortOut refunds, ReceivedPixPersistencePortOut receivedPix,
                               ExecuteReservationUseCase ledger, DomainEventPublisherPortOut events, InboxPortOut inbox,
                               TransactionRunnerPortOut tx) {
        this.refunds = refunds;
        this.receivedPix = receivedPix;
        this.ledger = ledger;
        this.events = events;
        this.inbox = inbox;
        this.tx = tx;
    }

    @Override
    public void apply(String consumerGroup, String messageId, String topic, int partition, long offset, String rtrId,
                      boolean settled, String reason, Instant at) {
        Refund known = refunds.findByRtrId(rtrId)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Devolução não encontrada para o rtrId."));
        if (settled) {
            ledger.confirm(rtrId);
        } else {
            ledger.cancel(rtrId);
        }
        tx.inTransaction(() -> {
            if (!inbox.firstTime(consumerGroup, messageId, topic, partition, offset)) {
                return;
            }
            Refund current = refunds.findByRtrId(rtrId).orElse(known);
            if (current.status().isFinal()) {
                return;                                              // já decidida: não reaplica
            }
            Refund updated = settled ? current.settled(at) : current.failed(reason);
            Refund saved = refunds.save(updated);
            if (!settled) {
                receivedPix.releaseRefundable(saved.receivedId(), saved.amount());
            }
            events.publish(new RefundEvent(settled ? "RefundSettled" : "RefundFailed", saved));
        });
    }
}
