package br.com.pixmanager.infrastructure.rest.ledger;

import br.com.pixmanager.shared.properties.CoreLedgerProperties;

import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.shared.exception.DependencyUnavailableException;
import br.com.pixmanager.infrastructure.rest.support.GatewayHttp;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.Map;

/**
 * Cliente HTTP do core (reservar/confirmar/cancelar), idempotente por {@code endToEndId} (header {@code Idempotency-Key}).
 * <b>Técnico × negócio decidido AQUI</b> (resilience-patterns): 4xx definitivo ⇒ {@link GatewayResult.Refused};
 * 429/503 ⇒ {@link DependencyUnavailableException}; 5xx/timeout/IO ⇒ {@link IntegrationException}. Circuit breaker
 * {@code core-ledger} (não abre por recusa de negócio, pois recusa nem é exceção). Retry: uma só camada — o job/consumer.
 */
@Component
public class CoreLedgerHttpAdapter implements LedgerGatewayPortOut {

    private final RestClient http;
    private final CircuitBreaker breaker;
    private final ObjectMapper mapper;

    public CoreLedgerHttpAdapter(CoreLedgerProperties props, CircuitBreakerRegistry registry, ObjectMapper mapper) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(props.readTimeout());
        this.http = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        this.breaker = registry.circuitBreaker("core-ledger");
        this.mapper = mapper;
    }

    @Override
    public GatewayResult reserve(LedgerReservation reservation) {
        return call(reservation, "/ledger/v1/reservations", Map.of(
                "idempotencyKey", reservation.idempotencyKey(), "accountId", reservation.accountId().toString(),
                "amount", reservation.amount().toBcb(), "purpose", reservation.purpose().name()));
    }

    @Override
    public GatewayResult confirm(LedgerReservation reservation) {
        return call(reservation, "/ledger/v1/reservations/" + reservation.idempotencyKey() + "/confirmation", Map.of());
    }

    @Override
    public GatewayResult cancel(LedgerReservation reservation) {
        return call(reservation, "/ledger/v1/reservations/" + reservation.idempotencyKey() + "/cancellation", Map.of());
    }

    private GatewayResult call(LedgerReservation reservation, String path, Map<String, String> body) {
        try {
            return breaker.executeSupplier(() -> exchange(reservation, path, body));
        } catch (CallNotPermittedException exception) {
            throw new DependencyUnavailableException("Circuito do core aberto", exception);
        }
    }

    private GatewayResult exchange(LedgerReservation reservation, String path, Map<String, String> body) {
        try {
            return http.post().uri(path)
                    .header("Idempotency-Key", reservation.idempotencyKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> translate(response.getStatusCode(), response.getBody()));
        } catch (ResourceAccessException exception) {
            throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, "Core indisponível (timeout/IO)", exception);
        }
    }

    private GatewayResult translate(HttpStatusCode status, java.io.InputStream bodyStream) throws IOException {
        JsonNode json = bodyStream == null ? mapper.createObjectNode() : mapper.readTree(bodyStream);
        if (status.is2xxSuccessful()) {
            return new GatewayResult.Done(json.path("ledgerRef").asText(null));
        }
        int code = status.value();
        if (GatewayHttp.isUnavailableStatus(code)) {
            throw new DependencyUnavailableException("Core respondeu " + code, null);
        }
        if (code >= 500) {
            throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, "Core respondeu " + code, null);
        }
        // 4xx = resposta definitiva de negócio: NUNCA retentar (4xx não pode ser tratado como 5xx)
        String reason = json.path("code").asText("");
        GatewayResult.Reason mapped = switch (reason) {
            case "INSUFFICIENT_FUNDS" -> GatewayResult.Reason.INSUFFICIENT_FUNDS;
            case "ACCOUNT_BLOCKED" -> GatewayResult.Reason.ACCOUNT_BLOCKED;
            default -> GatewayResult.Reason.OTHER;
        };
        return new GatewayResult.Refused(mapped, json.path("message").asText(""));
    }
}
