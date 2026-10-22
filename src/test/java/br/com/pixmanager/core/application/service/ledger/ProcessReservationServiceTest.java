package br.com.pixmanager.core.application.service.ledger;

import br.com.pixmanager.core.application.service.ledger.ExecuteReservationServiceTest.FakeGateway;
import br.com.pixmanager.core.application.service.ledger.ExecuteReservationServiceTest.InMemoryPersistence;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.enums.LedgerStatus;
import br.com.pixmanager.core.port.in.ledger.ReservationOutcomeHandler;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut.GatewayResult;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessReservationServiceTest {

    static final String E2E = "E12345678202610151403abcdefghij0";

    FixedClock clock = FixedClock.at("2026-10-15T14:03:00Z");
    InMemoryPersistence persistence = new InMemoryPersistence();
    FakeGateway gateway = new FakeGateway();
    RecordingHandler handler = new RecordingHandler();
    ProcessReservationService process;
    UUID id;

    /** Transação simulada: se o bloco lança, o estado do "banco" volta ao que era (rollback). */
    final TransactionRunnerPortOut transactionRunner = new TransactionRunnerPortOut() {
        @Override
        public <T> T inTransaction(Supplier<T> action) {
            var snapshot = new LinkedHashMap<>(persistence.store);
            try {
                return action.get();
            } catch (RuntimeException exception) {
                persistence.store.clear();
                persistence.store.putAll(snapshot);
                throw exception;
            }
        }
    };

    @BeforeEach
    void setUp() {
        var execute = new ExecuteReservationService(persistence, gateway, clock);
        process = new ProcessReservationService(execute, transactionRunner, List.of(handler));
        id = new RequestReservationService(persistence, new SequentialIds(), clock)
                .request(E2E, SequentialIds.id(9), LedgerPurpose.PAYMENT, "PAYMENT_ORDER", SequentialIds.id(5), Money.of("10.00")).id();
    }

    @Test
    void outcomeAndHandlerAreCommittedTogether() {
        gateway.onReserve = reservation -> new GatewayResult.Done("L");

        process.process(id);

        assertThat(persistence.findById(id).orElseThrow().status()).isEqualTo(LedgerStatus.RESERVED);
        assertThat(handler.reserved).isEqualTo(1);
    }

    @Test
    void whenTheHandlerFailsTheReservationStaysPendingSoTheSweeperRetries() {
        gateway.onReserve = reservation -> new GatewayResult.Done("L");
        handler.failure = new IllegalStateException("outbox indisponível");

        assertThatThrownBy(() -> process.process(id)).isSameAs(handler.failure);

        assertThat(persistence.findById(id).orElseThrow().status()).isEqualTo(LedgerStatus.PENDING);
        handler.failure = null;
        process.process(id);
        assertThat(persistence.findById(id).orElseThrow().status()).isEqualTo(LedgerStatus.RESERVED);
        assertThat(handler.reserved).isEqualTo(1);
    }

    @Test
    void refusalIsDispatchedToOnRefused() {
        gateway.onReserve = reservation -> new GatewayResult.Refused(GatewayResult.Reason.INSUFFICIENT_FUNDS, "sem saldo");

        process.process(id);

        assertThat(persistence.findById(id).orElseThrow().status()).isEqualTo(LedgerStatus.CANCELLED);
        assertThat(handler.refused).isEqualTo(1);
    }

    static class RecordingHandler implements ReservationOutcomeHandler {
        int reserved, refused;
        RuntimeException failure;

        public LedgerPurpose purpose() { return LedgerPurpose.PAYMENT; }

        public void onReserved(LedgerReservation reservation) {
            if (failure != null) { throw failure; }
            reserved++;
        }

        public void onRefused(LedgerReservation reservation) {
            if (failure != null) { throw failure; }
            refused++;
        }
    }
}
