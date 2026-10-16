package br.com.pixmanager.core.application.service.ledger;

import br.com.pixmanager.core.port.in.ledger.ExecuteReservationUseCase;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut.GatewayResult;
import br.com.pixmanager.core.port.out.persistence.LedgerReservationPersistencePortOut;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.enums.LedgerStatus;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.shared.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Sem {@code @Transactional}: a chamada ao core é HTTP e NÃO pode ficar dentro de transação de banco. Cada transição
 * persiste em transação própria (do adaptador). Técnico × negócio decidido aqui: {@code IntegrationException} agenda
 * retry e relança; {@code Refused} é definitivo e nunca é retentado.
 */
public class ExecuteReservationService implements ExecuteReservationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExecuteReservationService.class);
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);
    /** Teto de tentativas técnicas da reserva: esgotado, ela vira FAILED (e o fluxo dono devolve o limite). */
    static final int MAX_TECHNICAL_ATTEMPTS = 30;

    private final LedgerReservationPersistencePortOut persistence;
    private final LedgerGatewayPortOut gateway;
    private final Clock clock;

    public ExecuteReservationService(LedgerReservationPersistencePortOut persistence, LedgerGatewayPortOut gateway, Clock clock) {
        this.persistence = persistence;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Override
    public LedgerReservation find(UUID reservationId) {
        return persistence.findById(reservationId)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Reserva não encontrada"));
    }

    @Override
    public LedgerReservation reserve(UUID reservationId) {
        return save(decideReserve(reservationId));
    }

    @Override
    public LedgerReservation decideReserve(UUID reservationId) {
        LedgerReservation reservation = persistence.findById(reservationId)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Reserva não encontrada"));
        if (reservation.status() != LedgerStatus.PENDING) {
            return reservation;                                // idempotente: já decidida
        }
        return decide(reservation, gateway::reserve, r -> r.reserved(null), LedgerReservation::cancelled, true);
    }

    @Override
    public LedgerReservation save(LedgerReservation reservation) {
        return persistence.save(reservation);
    }

    @Override
    public LedgerReservation confirm(String idempotencyKey) {
        LedgerReservation reservation = byKey(idempotencyKey);
        if (reservation.status() == LedgerStatus.CONFIRMED) {
            return reservation;
        }
        return apply(reservation, gateway::confirm, LedgerReservation::confirmed, r -> r.failed("confirmação recusada"));
    }

    @Override
    public LedgerReservation cancel(String idempotencyKey) {
        LedgerReservation reservation = byKey(idempotencyKey);
        if (reservation.status().isFinal()) {
            return reservation;
        }
        if (reservation.status() == LedgerStatus.PENDING) {
            return persistence.save(reservation.cancelled());   // o core nunca viu a reserva: nada a cancelar lá
        }
        return apply(reservation, gateway::cancel, LedgerReservation::cancelled, LedgerReservation::cancelled);
    }

    private LedgerReservation byKey(String idempotencyKey) {
        return persistence.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Reserva não encontrada"));
    }

    private LedgerReservation apply(LedgerReservation reservation,
                                    java.util.function.Function<LedgerReservation, GatewayResult> call,
                                    java.util.function.UnaryOperator<LedgerReservation> onDone,
                                    java.util.function.UnaryOperator<LedgerReservation> onRefused) {
        return persistence.save(decide(reservation, call, onDone, onRefused, false));
    }

    /**
     * Chama o core e devolve o NOVO estado SEM persisti-lo (quem chama decide a transação). A falha técnica é persistida aqui
     * (backoff) e relançada; com {@code capAttempts}, esgotado o teto a reserva sai como FAILED em vez de ser retentada para sempre.
     */
    private LedgerReservation decide(LedgerReservation reservation,
                                     java.util.function.Function<LedgerReservation, GatewayResult> call,
                                     java.util.function.UnaryOperator<LedgerReservation> onDone,
                                     java.util.function.UnaryOperator<LedgerReservation> onRefused, boolean capAttempts) {
        GatewayResult result;
        try {
            result = call.apply(reservation);
        } catch (IntegrationException e) {
            if (capAttempts && reservation.attempts() + 1 >= MAX_TECHNICAL_ATTEMPTS) {
                log.error("Reserva {} esgotou {} tentativas técnicas no core; marcada FAILED", reservation.id(), MAX_TECHNICAL_ATTEMPTS, e);
                return reservation.failed("tentativas esgotadas: " + e.getMessage());
            }
            Instant next = clock.instant().plus(backoff(reservation.attempts()));
            persistence.save(reservation.technicalFailure(e.getMessage(), next));
            log.warn("Falha técnica no core [{}] (tentativa {}, reserva {}): {}", e.getCode(), reservation.attempts() + 1, reservation.id(), e.getMessage());
            throw e;
        }
        return switch (result) {
            case GatewayResult.Done done -> onDone.apply(reservation).withLedgerRef(done.ledgerRef());
            case GatewayResult.Refused refused -> {
                log.info("Core recusou ({}): reserva {}", refused.reason(), reservation.id());
                yield onRefused.apply(reservation).withError(refused.reason().name());
            }
        };
    }

    /** Backoff exponencial limitado a 10 min; o jitter é aplicado pelo agendador do job. */
    static Duration backoff(int attempts) {
        long millis = BASE_BACKOFF.toMillis() * (1L << Math.min(attempts, 10));
        return Duration.ofMillis(Math.min(millis, MAX_BACKOFF.toMillis()));
    }
}
