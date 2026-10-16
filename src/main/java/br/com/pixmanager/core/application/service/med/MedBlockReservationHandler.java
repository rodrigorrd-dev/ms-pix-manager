package br.com.pixmanager.core.application.service.med;

import br.com.pixmanager.core.port.in.ledger.ReservationOutcomeHandler;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.entity.MedCase;
import br.com.pixmanager.core.domain.entity.event.MedCaseEvent;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.enums.MedStatus;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.MedCasePersistencePortOut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * Desfecho do bloqueio cautelar no core. Reservado ⇒ registra o bloqueio e o valor no caso. Recusado (sem saldo, conta bloqueada) ⇒ o
 * caso segue ACCEPTED SEM valor bloqueado e o histórico guarda o motivo: a operação decide (não há bloqueio parcial automático).
 */
public class MedBlockReservationHandler implements ReservationOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(MedBlockReservationHandler.class);

    private final MedCasePersistencePortOut cases;
    private final DomainEventPublisherPortOut events;
    private final Clock clock;

    public MedBlockReservationHandler(MedCasePersistencePortOut cases, DomainEventPublisherPortOut events, Clock clock) {
        this.cases = cases;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public LedgerPurpose purpose() {
        return LedgerPurpose.MED_BLOCK;
    }

    @Override
    public void onReserved(LedgerReservation reservation) {
        MedCase current = cases.findById(reservation.referenceId()).orElseThrow();
        if (current.status() != MedStatus.ACCEPTED) {
            log.warn("Bloqueio MED {} reservado, mas o caso {} já está {}: o cancelamento do caso libera o valor", reservation.id(), current.id(), current.status());
            return;
        }
        MedCase saved = cases.save(current.blocked(reservation.amount()));
        cases.insertBlock(saved.id(), saved.accountId(), reservation.amount().value(), reservation.id());
        cases.appendHistory(saved.id(), saved.status(), saved.status(), "system", "Bloqueio cautelar aplicado: " + reservation.amount().toBcb(), clock.instant());
        events.publish(new MedCaseEvent("BLOCK_APPLIED", saved));
    }

    @Override
    public void onRefused(LedgerReservation reservation) {
        MedCase current = cases.findById(reservation.referenceId()).orElseThrow();
        cases.appendHistory(current.id(), current.status(), current.status(), "system", "Bloqueio recusado pelo core: " + reservation.lastError(), clock.instant());
        events.publish(new MedCaseEvent("BLOCK_REFUSED", current));
    }
}
