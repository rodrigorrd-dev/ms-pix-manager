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
        LedgerReservation p = pending();
        gateway.onReserve = r -> new GatewayResult.Done("LEDGER-1");
        LedgerReservation reserved = execute.reserve(p.id());
        assertThat(reserved.status()).isEqualTo(LedgerStatus.RESERVED);
        assertThat(reserved.ledgerRef()).isEqualTo("LEDGER-1");

        execute.reserve(p.id());
        assertThat(gateway.reserveCalls).isEqualTo(1);
    }

    @Test
    void businessRefusalIsDefinitiveAndNeverRetried() {
        LedgerReservation p = pending();
        gateway.onReserve = r -> new GatewayResult.Refused(GatewayResult.Reason.INSUFFICIENT_FUNDS, "sem saldo");
        LedgerReservation result = execute.reserve(p.id());
        assertThat(result.status()).isEqualTo(LedgerStatus.CANCELLED);
        assertThat(result.lastError()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(execute.reserve(p.id()).status()).isEqualTo(LedgerStatus.CANCELLED);
        assertThat(gateway.reserveCalls).isEqualTo(1);
    }

    @Test
    void technicalFailureSchedulesBackoffKeepsPendingAndRethrows() {
        LedgerReservation p = pending();
        gateway.onReserve = r -> { throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, "timeout", null); };

        assertThatThrownBy(() -> execute.reserve(p.id())).isInstanceOf(IntegrationException.class);
        LedgerReservation after = persistence.findById(p.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(LedgerStatus.PENDING);
        assertThat(after.attempts()).isEqualTo(1);
        assertThat(after.nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(5)));

        assertThatThrownBy(() -> execute.reserve(p.id())).isInstanceOf(IntegrationException.class);
        assertThat(persistence.findById(p.id()).orElseThrow().nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(10)));
    }

    @Test
    void technicalFailuresAreCappedAndTheReservationEndsFailed() {
        LedgerReservation p = pending();
        LedgerReservation worn = p;
        for (int i = 0; i < ExecuteReservationService.MAX_TECHNICAL_ATTEMPTS - 1; i++) {
            worn = worn.technicalFailure("timeout", clock.instant());
        }
        persistence.save(worn);
        gateway.onReserve = r -> { throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, "timeout", null); };

        LedgerReservation result = execute.reserve(p.id());

        assertThat(result.status()).isEqualTo(LedgerStatus.FAILED);
        assertThat(persistence.findById(p.id()).orElseThrow().status()).isEqualTo(LedgerStatus.FAILED);
    }

    @Test
    void decideReserveDoesNotPersistTheOutcome() {
        LedgerReservation p = pending();
        gateway.onReserve = r -> new GatewayResult.Done("L");

        LedgerReservation decided = execute.decideReserve(p.id());

        assertThat(decided.status()).isEqualTo(LedgerStatus.RESERVED);
        assertThat(persistence.findById(p.id()).orElseThrow().status()).isEqualTo(LedgerStatus.PENDING);
    }

    @Test
    void backoffIsCappedAtTenMinutes() {
        assertThat(ExecuteReservationServiceBackoff.of(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(ExecuteReservationServiceBackoff.of(30)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void confirmIsIdempotentAndCancelAfterConfirmIsInvalid() {
        LedgerReservation p = pending();
        gateway.onReserve = r -> new GatewayResult.Done("L");
        gateway.onConfirm = r -> new GatewayResult.Done("L");
        execute.reserve(p.id());

        assertThat(execute.confirm(E2E).status()).isEqualTo(LedgerStatus.CONFIRMED);
        assertThat(execute.confirm(E2E).status()).isEqualTo(LedgerStatus.CONFIRMED);
        assertThat(gateway.confirmCalls).isEqualTo(1);
        // cancelar depois de confirmar não faz nada (estado final) — nunca devolve dinheiro já liquidado
        assertThat(execute.cancel(E2E).status()).isEqualTo(LedgerStatus.CONFIRMED);
    }

    @Test
    void cancelOfPendingDoesNotCallTheCoreBecauseItNeverSawTheReservation() {
        LedgerReservation p = pending();
        assertThat(execute.cancel(E2E).status()).isEqualTo(LedgerStatus.CANCELLED);
        assertThat(gateway.cancelCalls).isZero();
        assertThat(p.status()).isEqualTo(LedgerStatus.PENDING);
    }

    @Test
    void cancelOfReservedCallsTheCore() {
        LedgerReservation p = pending();
        gateway.onReserve = r -> new GatewayResult.Done("L");
        gateway.onCancel = r -> new GatewayResult.Done("L");
        execute.reserve(p.id());
        assertThat(execute.cancel(E2E).status()).isEqualTo(LedgerStatus.CANCELLED);
        assertThat(gateway.cancelCalls).isEqualTo(1);
    }

    @Test
    void invalidTransitionIsRejected() {
        LedgerReservation p = pending();
        assertThatThrownBy(() -> p.confirmed()).isInstanceOf(InvalidTransitionException.class);
        assertThat(LedgerStatus.RESERVED.canTransitionTo(LedgerStatus.PENDING)).isFalse();
    }

    /** acesso ao backoff package-private sem expor no service */
    static final class ExecuteReservationServiceBackoff {
        static Duration of(int attempts) {
            try {
                var m = ExecuteReservationService.class.getDeclaredMethod("backoff", int.class);
                m.setAccessible(true);
                return (Duration) m.invoke(null, attempts);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static class InMemoryPersistence implements LedgerReservationPersistencePortOut {
        final Map<UUID, LedgerReservation> store = new LinkedHashMap<>();

        public LedgerReservation save(LedgerReservation r) {
            store.put(r.id(), r);
            return r;
        }

        public Optional<LedgerReservation> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        public Optional<LedgerReservation> findByIdempotencyKey(String key) {
            return store.values().stream().filter(r -> r.idempotencyKey().equals(key)).findFirst();
        }

        public List<LedgerReservation> findDue(Instant now, int limit) {
            return new ArrayList<>(store.values());
        }
    }

    static class FakeGateway implements LedgerGatewayPortOut {
        Function<LedgerReservation, GatewayResult> onReserve = r -> new GatewayResult.Done("x");
        Function<LedgerReservation, GatewayResult> onConfirm = r -> new GatewayResult.Done("x");
        Function<LedgerReservation, GatewayResult> onCancel = r -> new GatewayResult.Done("x");
        int reserveCalls, confirmCalls, cancelCalls;

        public GatewayResult reserve(LedgerReservation r) { reserveCalls++; return onReserve.apply(r); }
        public GatewayResult confirm(LedgerReservation r) { confirmCalls++; return onConfirm.apply(r); }
        public GatewayResult cancel(LedgerReservation r) { cancelCalls++; return onCancel.apply(r); }
    }
}
