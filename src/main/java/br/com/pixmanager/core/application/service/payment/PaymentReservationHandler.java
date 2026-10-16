package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.entity.LimitDecision;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.event.PaymentOrderEvent;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.port.in.ledger.ReservationOutcomeHandler;
import br.com.pixmanager.core.port.in.limit.ConsumeLimitUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;

import java.time.ZoneId;

/**
 * Depois de reservar no core: {@code PaymentReserved} (o spi-gateway emite o pacs.008). Se o core recusar: ordem REJECTED com o
 * código ISO 20022 correspondente e o LIMITE consumido é devolvido (compensação).
 */
public class PaymentReservationHandler implements ReservationOutcomeHandler {

    private final PaymentOrderPersistencePortOut persistence;
    private final ConsumeLimitUseCase limits;
    private final DomainEventPublisherPortOut events;
    private final ZoneId zone;

    public PaymentReservationHandler(PaymentOrderPersistencePortOut persistence, ConsumeLimitUseCase limits,
                                     DomainEventPublisherPortOut events, ZoneId zone) {
        this.persistence = persistence;
        this.limits = limits;
        this.events = events;
        this.zone = zone;
    }

    @Override
    public LedgerPurpose purpose() {
        return LedgerPurpose.PAYMENT;
    }

    @Override
    public void onReserved(LedgerReservation reservation) {
        PaymentOrder reserved = persistence.save(load(reservation).reserved());
        events.publish(new PaymentOrderEvent("PaymentReserved", reserved));
    }

    @Override
    public void onRefused(LedgerReservation reservation) {
        PaymentOrder order = load(reservation);
        PaymentOrder rejected = persistence.save(order.rejected(isoCodeFor(reservation.lastError()), "Reserva recusada pelo core: " + reservation.lastError()));
        limits.release(order.payerAccountId(), new LimitDecision.Allowed(order.requestedAt().atZone(zone).toLocalDate(),
                !order.deviceRegistered(), order.amount()));
        events.publish(new PaymentOrderEvent("PaymentRejected", rejected));
    }

    /** Motivo do core → código ISO 20022 da família do Pix (AM04 saldo insuficiente, AC06 conta bloqueada, MS03 outro). */
    static String isoCodeFor(String ledgerReason) {
        return switch (ledgerReason == null ? "" : ledgerReason) {
            case "INSUFFICIENT_FUNDS" -> "AM04";
            case "ACCOUNT_BLOCKED" -> "AC06";
            default -> "MS03";
        };
    }

    private PaymentOrder load(LedgerReservation reservation) {
        return persistence.findByEndToEndId(reservation.idempotencyKey())
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Ordem da reserva não encontrada."));
    }
}
