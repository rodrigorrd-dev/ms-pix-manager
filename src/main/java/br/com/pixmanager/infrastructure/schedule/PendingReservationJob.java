package br.com.pixmanager.infrastructure.schedule;

import br.com.pixmanager.core.port.in.ledger.ProcessReservationUseCase;
import br.com.pixmanager.core.port.out.persistence.LedgerReservationPersistencePortOut;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.shared.exception.IntegrationException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Sweeper: chama o core para reservas PENDING (inclusive as que falharam tecnicamente). ShedLock: 1 por cluster. */
@Component
public class PendingReservationJob {

    private static final Logger log = LoggerFactory.getLogger(PendingReservationJob.class);

    private final LedgerReservationPersistencePortOut persistence;
    private final ProcessReservationUseCase process;
    private final Clock clock;

    PendingReservationJob(LedgerReservationPersistencePortOut persistence, ProcessReservationUseCase process, Clock clock) {
        this.persistence = persistence;
        this.process = process;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pix.ledger.sweeper-delay:PT2S}")
    @SchedulerLock(name = "ledger-pending-reservations", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    void run() {
        for (LedgerReservation due : persistence.findDue(clock.instant(), 100)) {
            try {
                process.process(due.id());
            } catch (IntegrationException e) {
                // já registrada com backoff pelo service; segue para as demais (uma dependência lenta não trava o lote)
                log.debug("Reserva {} adiada [{}]: {}", due.id(), e.getCode(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Erro inesperado na reserva {}", due.id(), e);
            }
        }
    }
}
