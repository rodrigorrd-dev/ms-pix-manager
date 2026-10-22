package br.com.pixmanager.infrastructure.rest.ledger;

import br.com.pixmanager.infrastructure.rest.ledger.CoreLedgerHttpAdapter;
import br.com.pixmanager.shared.properties.CoreLedgerProperties;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut.GatewayResult;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.shared.exception.DependencyUnavailableException;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.support.FixedClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contrato HTTP de saída: 4xx = negócio (nunca retentado), 429/503 = indisponível, 5xx/timeout = técnico. */
@DisplayName("Contrato core/ledger (WireMock): técnico × negócio por status")
class CoreLedgerHttpAdapterContractTest {

    static final String E2E = "E12345678202610151403abcdefghij0";

    WireMockServer wireMock;
    CoreLedgerHttpAdapter adapter;
    LedgerReservation reservation;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        adapter = new CoreLedgerHttpAdapter(new CoreLedgerProperties("http://localhost:" + wireMock.port(),
                Duration.ofMillis(300), Duration.ofMillis(400)), CircuitBreakerRegistry.ofDefaults(), new ObjectMapper());
        reservation = LedgerReservation.pending(UUID.randomUUID(), E2E, UUID.randomUUID(), LedgerPurpose.PAYMENT, "PAYMENT_ORDER",
                UUID.randomUUID(), Money.of("250.00"), FixedClock.at("2026-10-15T14:03:00Z").instant());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void shouldReserveAndSendEndToEndIdAsIdempotencyKey() {
        wireMock.stubFor(post(urlEqualTo("/ledger/v1/reservations")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ledgerRef\":\"L-9\"}")));

        GatewayResult result = adapter.reserve(reservation);

        assertThat(result).isEqualTo(new GatewayResult.Done("L-9"));
        wireMock.verify(postRequestedFor(urlEqualTo("/ledger/v1/reservations")).withHeader("Idempotency-Key", equalTo(E2E)));
    }

    @Test
    void shouldMapBusinessRefusalWithoutThrowing() {
        wireMock.stubFor(post(urlEqualTo("/ledger/v1/reservations")).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json").withBody("{\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"saldo\"}")));

        assertThat(adapter.reserve(reservation))
                .isEqualTo(new GatewayResult.Refused(GatewayResult.Reason.INSUFFICIENT_FUNDS, "saldo"));
    }

    @Test
    void a404FromTheCoreIsABusinessAnswerNotARetryableFailure() {
        wireMock.stubFor(post(urlEqualTo("/ledger/v1/reservations")).willReturn(aResponse().withStatus(404)));
        assertThat(adapter.reserve(reservation)).isInstanceOf(GatewayResult.Refused.class);
    }

    @Test
    void credentialOrTimeoutStatusesAreTechnicalNeverARefusal() {
        for (int status : new int[]{401, 403, 408, 425}) {
            wireMock.stubFor(post(urlEqualTo("/ledger/v1/reservations")).willReturn(aResponse().withStatus(status)));
            assertThatThrownBy(() -> adapter.reserve(reservation))
                    .as("status %s", status).isInstanceOf(DependencyUnavailableException.class);
        }
    }

    @Test
    void shouldTreat429And503AsDependencyUnavailable() {
        wireMock.stubFor(post(urlEqualTo("/ledger/v1/reservations")).willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> adapter.reserve(reservation)).isInstanceOf(DependencyUnavailableException.class);
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo("/ledger/v1/reservations")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> adapter.reserve(reservation)).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void shouldTreat5xxAsTechnicalFailure() {
        wireMock.stubFor(post(urlEqualTo("/ledger/v1/reservations")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> adapter.reserve(reservation))
                .isInstanceOf(IntegrationException.class).isNotInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void shouldTreatTimeoutAsTechnicalFailure() {
        wireMock.stubFor(post(urlEqualTo("/ledger/v1/reservations")).willReturn(aResponse().withStatus(200).withFixedDelay(1_500)
                .withBody("{\"ledgerRef\":\"x\"}")));
        assertThatThrownBy(() -> adapter.reserve(reservation)).isInstanceOf(IntegrationException.class);
    }

    @Test
    void confirmAndCancelUseTheEndToEndIdInThePath() {
        wireMock.stubFor(post(urlEqualTo("/ledger/v1/reservations/" + E2E + "/confirmation")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"ledgerRef\":\"L\"}")));
        wireMock.stubFor(post(urlEqualTo("/ledger/v1/reservations/" + E2E + "/cancellation")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        assertThat(adapter.confirm(reservation)).isInstanceOf(GatewayResult.Done.class);
        assertThat(adapter.cancel(reservation)).isInstanceOf(GatewayResult.Done.class);
    }
}
