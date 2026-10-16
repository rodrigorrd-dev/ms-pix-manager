package br.com.pixmanager.infrastructure.rest.dict;

import br.com.pixmanager.core.domain.enums.MedCaseType;
import br.com.pixmanager.core.port.out.MedGatewayPortOut;
import br.com.pixmanager.infrastructure.rest.support.GatewayHttp;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.IntegrationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Notificação de infração (MED) via {@code dict-gateway} — contrato RASCUNHO (docs/05 §6b), a confirmar com o gateway real:
 * {@code POST /dict/v1/funds-recoveries} {@code {endToEndId, type, description, layer}} ⇒ 2xx {@code {id}};
 * {@code POST /dict/v1/funds-recoveries/{id}/cancellation}. 400/404/409/422 = recusa de negócio; 5xx/timeout = técnico.
 */
@Component
public class MedGatewayHttpAdapter implements MedGatewayPortOut {

    private static final String DEPENDENCY = "dict-gateway";

    private final RestClient http;
    private final CircuitBreaker breaker;

    public MedGatewayHttpAdapter(@Value("${pix.dict-gateway.base-url}") String baseUrl,
                                 @Value("${pix.dict-gateway.connect-timeout:500ms}") Duration connectTimeout,
                                 @Value("${pix.dict-gateway.read-timeout:3s}") Duration readTimeout, CircuitBreakerRegistry registry) {
        this.http = GatewayHttp.client(baseUrl, connectTimeout, readTimeout);
        this.breaker = registry.circuitBreaker(DEPENDENCY);
    }

    @Override
    public String report(String endToEndId, MedCaseType type, String description, int layer) {
        Map<String, Object> body = Map.of("endToEndId", endToEndId, "type", type.name(), "description", description, "layer", layer);
        return GatewayHttp.call(breaker, DEPENDENCY, () -> http.post().uri("/dict/v1/funds-recoveries").contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", UUID.randomUUID().toString()).body(body).exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    GatewayHttp.failOnTechnicalStatus(status, DEPENDENCY);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        Map<?, ?> json = response.bodyTo(Map.class);
                        Object id = json == null ? null : json.get("id");
                        if (id == null) {
                            throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, DEPENDENCY + " não devolveu o id do pedido de recuperação", null);
                        }
                        return id.toString();
                    }
                    if (status == 400 || status == 404 || status == 409 || status == 422) {
                        throw new BusinessException(ErrorCodes.BUSINESS_RULE, "O DICT recusou a notificação de infração.");
                    }
                    throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, DEPENDENCY + " respondeu " + status + " (contrato/URL inesperados)", null);
                }));
    }

    @Override
    public void cancel(String fundsRecoveryId) {
        GatewayHttp.call(breaker, DEPENDENCY, () -> http.post().uri("/dict/v1/funds-recoveries/{id}/cancellation", fundsRecoveryId)
                .header("X-Request-Id", UUID.randomUUID().toString()).exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    GatewayHttp.failOnTechnicalStatus(status, DEPENDENCY);
                    if (response.getStatusCode().is2xxSuccessful() || status == 404 || status == 409) {     // já cancelado/encerrado: idempotente
                        return null;
                    }
                    throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, DEPENDENCY + " respondeu " + status + " (contrato/URL inesperados)", null);
                }));
    }
}
