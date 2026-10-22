package br.com.pixmanager.core.application.service.ledger;

import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut.GatewayResult;
import br.com.pixmanager.core.port.out.persistence.LedgerReservationPersistencePortOut;
import br.com.pixmanager.core.application.service.ledger.ExecuteReservationService;
import br.com.pixmanager.core.application.service.ledger.RequestReservationService;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.enums.LedgerStatus;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.shared.exception.InvalidTransitionException;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Ledger — reservar → confirmar|cancelar idempotente por endToEndId (decisão A2)")
class ExecuteReservationServiceTest {

    static final String E2E = "E12345678202610151403abcdefghij0";

    FixedClock clock = FixedClock.at("2026-10-15T14:03:00Z");
    InMemoryPersistence persistence = new InMemoryPersistence();
    FakeGateway gateway = new FakeGateway();
    RequestReservationService request;
    ExecuteReservationService execute;

    @BeforeEach
    void setUp() {
        request = new RequestReservationService(persistence, new SequentialIds(), clock);
        execute = new ExecuteReservationService(persistence, gateway, clock);
    }

    private LedgerReservation pending() {
        return request.request(E2E, SequentialIds.id(9), LedgerPurpose.PAYMENT, "PAYMENT_ORDER", SequentialIds.id(5), Money.of("250.00"));
    }

    @Test
    void requestShouldBeIdempotentByKey() {
        LedgerReservation first = pending();
        LedgerReservation second = pending();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(persistence.store).hasSize(1);
        assertThat(first.status()).isEqualTo(LedgerStatus.PENDING);
    }

    @Test
    void shouldReserveAndNotCallCoreAgainWhenAlreadyReserved() {
        LedgerReservation reservation = pending();
        gateway.onReserve = ledgerReservation -> new GatewayResult.Done("LEDGER-1");
        LedgerReservation reserved = execute.reserve(reservation.id());
        assertThat(reserved.status()).isEqualTo(LedgerStatus.RESERVED);
        assertThat(reserved.ledgerRef()).isEqualTo("LEDGER-1");

        execute.reserve(reservation.id());
        assertThat(gateway.reserveCalls).isEqualTo(1);
    }

    @Test
    void businessRefusalIsDefinitiveAndNeverRetried() {
        LedgerReservation reservation = pending();
        gateway.onReserve = ledgerReservation -> new GatewayResult.Refused(GatewayResult.Reason.INSUFFICIENT_FUNDS, "sem saldo");
        LedgerReservation result = execute.reserve(reservation.id());
        assertThat(result.status()).isEqualTo(LedgerStatus.CANCELLED);
        assertThat(result.lastError()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(execute.reserve(reservation.id()).status()).isEqualTo(LedgerStatus.CANCELLED);
        assertThat(gateway.reserveCalls).isEqualTo(1);
    }

    @Test
    void technicalFailureSchedulesBackoffKeepsPendingAndRethrows() {
        LedgerReservation reservation = pending();
        gateway.onReserve = ledgerReservation -> { throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, "timeout", null); };

        assertThatThrownBy(() -> execute.reserve(reservation.id())).isInstanceOf(IntegrationException.class);
        LedgerReservation after = persistence.findById(reservation.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(LedgerStatus.PENDING);
        assertThat(after.attempts()).isEqualTo(1);
        assertThat(after.nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(5)));

        assertThatThrownBy(() -> execute.reserve(reservation.id())).isInstanceOf(IntegrationException.class);
        assertThat(persistence.findById(reservation.id()).orElseThrow().nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(10)));
    }

    @Test
    void technicalFailuresAreCappedAndTheReservationEndsFailed() {
        LedgerReservation reservation = pending();
        LedgerReservation worn = reservation;
        for (int index = 0; index < ExecuteReservationService.MAX_TECHNICAL_ATTEMPTS - 1; index++) {
            worn = worn.technicalFailure("timeout", clock.instant());
        }
        persistence.save(worn);
        gateway.onReserve = ledgerReservation -> { throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, "timeout", null); };

        LedgerReservation result = execute.reserve(reservation.id());

        assertThat(result.status()).isEqualTo(LedgerStatus.FAILED);
        assertThat(persistence.findById(reservation.id()).orElseThrow().status()).isEqualTo(LedgerStatus.FAILED);
    }

    @Test
    void decideReserveDoesNotPersistTheOutcome() {
        LedgerReservation reservation = pending();
        gateway.onReserve = ledgerReservation -> new GatewayResult.Done("L");

        LedgerReservation decided = execute.decideReserve(reservation.id());

        assertThat(decided.status()).isEqualTo(LedgerStatus.RESERVED);
        assertThat(persistence.findById(reservation.id()).orElseThrow().status()).isEqualTo(LedgerStatus.PENDING);
    }

    @Test
    void backoffIsCappedAtTenMinutes() {
        assertThat(ExecuteReservationServiceBackoff.of(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(ExecuteReservationServiceBackoff.of(30)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void confirmIsIdempotentAndCancelAfterConfirmIsInvalid() {
        LedgerReservation reservation = pending();
        gateway.onReserve = ledgerReservation -> new GatewayResult.Done("L");
        gateway.onConfirm = ledgerReservation -> new GatewayResult.Done("L");
        execute.reserve(reservation.id());

        assertThat(execute.confirm(E2E).status()).isEqualTo(LedgerStatus.CONFIRMED);
        assertThat(execute.confirm(E2E).status()).isEqualTo(LedgerStatus.CONFIRMED);
        assertThat(gateway.confirmCalls).isEqualTo(1);
        // cancelar depois de confirmar não faz nada (estado final) — nunca devolve dinheiro já liquidado
        assertThat(execute.cancel(E2E).status()).isEqualTo(LedgerStatus.CONFIRMED);
    }

    @Test
    void cancelOfPendingDoesNotCallTheCoreBecauseItNeverSawTheReservation() {
        LedgerReservation reservation = pending();
        assertThat(execute.cancel(E2E).status()).isEqualTo(LedgerStatus.CANCELLED);
        assertThat(gateway.cancelCalls).isZero();
        assertThat(reservation.status()).isEqualTo(LedgerStatus.PENDING);
    }

    @Test
    void cancelOfReservedCallsTheCore() {
        LedgerReservation reservation = pending();
        gateway.onReserve = ledgerReservation -> new GatewayResult.Done("L");
        gateway.onCancel = ledgerReservation -> new GatewayResult.Done("L");
        execute.reserve(reservation.id());
        assertThat(execute.cancel(E2E).status()).isEqualTo(LedgerStatus.CANCELLED);
        assertThat(gateway.cancelCalls).isEqualTo(1);
    }

    @Test
    void invalidTransitionIsRejected() {
        LedgerReservation reservation = pending();
        assertThatThrownBy(() -> reservation.confirmed()).isInstanceOf(InvalidTransitionException.class);
        assertThat(LedgerStatus.RESERVED.canTransitionTo(LedgerStatus.PENDING)).isFalse();
    }

    /** acesso ao backoff package-private sem expor no service */
    static final class ExecuteReservationServiceBackoff {
        static Duration of(int attempts) {
            try {
                var backoffMethod = ExecuteReservationService.class.getDeclaredMethod("backoff", int.class);
                backoffMethod.setAccessible(true);
                return (Duration) backoffMethod.invoke(null, attempts);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    static class InMemoryPersistence implements LedgerReservationPersistencePortOut {
        final Map<UUID, LedgerReservation> store = new LinkedHashMap<>();

        public LedgerReservation save(LedgerReservation reservation) {
            store.put(reservation.id(), reservation);
            return reservation;
        }

        public Optional<LedgerReservation> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        public Optional<LedgerReservation> findByIdempotencyKey(String key) {
            return store.values().stream().filter(reservation -> reservation.idempotencyKey().equals(key)).findFirst();
        }

        public List<LedgerReservation> findDue(Instant now, int limit) {
            return new ArrayList<>(store.values());
        }
    }

    static class FakeGateway implements LedgerGatewayPortOut {
        Function<LedgerReservation, GatewayResult> onReserve = ledgerReservation -> new GatewayResult.Done("x");
        Function<LedgerReservation, GatewayResult> onConfirm = ledgerReservation -> new GatewayResult.Done("x");
        Function<LedgerReservation, GatewayResult> onCancel = ledgerReservation -> new GatewayResult.Done("x");
        int reserveCalls, confirmCalls, cancelCalls;

        public GatewayResult reserve(LedgerReservation reservation) { reserveCalls++; return onReserve.apply(reservation); }
        public GatewayResult confirm(LedgerReservation reservation) { confirmCalls++; return onConfirm.apply(reservation); }
        public GatewayResult cancel(LedgerReservation reservation) { cancelCalls++; return onCancel.apply(reservation); }
    }
}
