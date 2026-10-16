package br.com.pixmanager.core.application.service.refund;

import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.domain.entity.event.RefundEvent;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.port.in.ledger.ReservationOutcomeHandler;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.RefundPersistencePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;

/**
 * Depois de reservar no core: {@code RefundReserved} (o spi-gateway emite o pacs.004). Se o core recusar (saldo/conta
 * bloqueada, BR-REF-07): a devolução vira NAO_REALIZADO e o valor devolvível reservado é LIBERADO (compensação).
 */
public class RefundReservationHandler implements ReservationOutcomeHandler {

    private final RefundPersistencePortOut refunds;
    private final ReceivedPixPersistencePortOut receivedPix;
    private final DomainEventPublisherPortOut events;

    public RefundReservationHandler(RefundPersistencePortOut refunds, ReceivedPixPersistencePortOut receivedPix,
                                    DomainEventPublisherPortOut events) {
        this.refunds = refunds;
        this.receivedPix = receivedPix;
        this.events = events;
    }

    @Override
    public LedgerPurpose purpose() {
        return LedgerPurpose.REFUND;
    }

    @Override
    public void onReserved(LedgerReservation reservation) {
        events.publish(new RefundEvent("RefundReserved", load(reservation)));
    }

    @Override
    public void onRefused(LedgerReservation reservation) {
        Refund refund = load(reservation);
        Refund failed = refunds.save(refund.failed("Reserva recusada pelo core: " + reservation.lastError()));
        receivedPix.releaseRefundable(failed.receivedId(), failed.amount());
        events.publish(new RefundEvent("RefundFailed", failed));
    }

    private Refund load(LedgerReservation reservation) {
        return refunds.findByRtrId(reservation.idempotencyKey())
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Devolução da reserva não encontrada."));
    }
}
