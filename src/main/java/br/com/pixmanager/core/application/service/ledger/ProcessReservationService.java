package br.com.pixmanager.core.application.service.ledger;

import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.enums.LedgerStatus;
import br.com.pixmanager.core.port.in.ledger.ExecuteReservationUseCase;
import br.com.pixmanager.core.port.in.ledger.ProcessReservationUseCase;
import br.com.pixmanager.core.port.in.ledger.ReservationOutcomeHandler;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orquestra: {@code reservar} no core (HTTP, SEM transação) e depois chama o handler do fluxo (COM transação). Falha técnica
 * propaga (o sweeper tenta de novo); recusa de negócio vira {@code onRefused} — nunca retentada.
 */
public class ProcessReservationService implements ProcessReservationUseCase {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProcessReservationService.class);

    private final ExecuteReservationUseCase execute;
    private final TransactionRunnerPortOut transactionRunner;
    private final Map<LedgerPurpose, ReservationOutcomeHandler> handlers = new EnumMap<>(LedgerPurpose.class);

    public ProcessReservationService(ExecuteReservationUseCase execute, TransactionRunnerPortOut transactionRunner,
                                     List<ReservationOutcomeHandler> handlerList) {
        this.execute = execute;
        this.transactionRunner = transactionRunner;
        handlerList.forEach(handler -> handlers.put(handler.purpose(), handler));
    }

    /**
     * O resultado da reserva e o efeito no fluxo dono (ordem/devolução/limite) são gravados na MESMA transação: se o handler
     * falhar, a reserva continua PENDING e o sweeper repete — nunca fica "decidida" sem o fluxo dono saber.
     */
    @Override
    public void process(UUID reservationId) {
        LedgerReservation before = execute.find(reservationId);
        if (before.status() != LedgerStatus.PENDING) {
            return;                                     // já decidida: idempotente
        }
        LedgerReservation decided = execute.decideReserve(reservationId);        // HTTP, sem transação
        if (decided.status() == LedgerStatus.PENDING) {
            return;                                     // já decidida por outro (corrida): nada a aplicar
        }
        transactionRunner.inTransaction(() -> {
            LedgerReservation after = execute.save(decided);
            ReservationOutcomeHandler handler = handlers.get(after.purpose());
            if (handler == null) {
                // a decisão do core já foi gravada, mas nenhum fluxo a aplica: nunca engolir em silêncio
                log.error("Reserva {} decidida ({}) sem handler para a finalidade {}: nenhum fluxo foi notificado", after.id(), after.status(), after.purpose());
                return;
            }
            switch (after.status()) {
                case RESERVED -> handler.onReserved(after);
                case CANCELLED, FAILED -> handler.onRefused(after);
                default -> { /* sem efeito */ }
            }
        });
    }
}
