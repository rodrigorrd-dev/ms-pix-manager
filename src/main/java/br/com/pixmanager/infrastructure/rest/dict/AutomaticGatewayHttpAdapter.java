package br.com.pixmanager.infrastructure.rest.dict;

import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.port.out.AutomaticGatewayPortOut;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Resposta ao recebedor via gateway — contrato RASCUNHO (docs/05 §6b), a confirmar: {@code POST /spi/v1/automatic/authorizations/{idRec}/approval|rejection|cancellation}.
 * 2xx e 409 (já decidido, idempotente) = ok; 400/404/422 = recusa de negócio; 5xx/timeout = técnico.
 */
@Component
public class AutomaticGatewayHttpAdapter implements AutomaticGatewayPortOut {

    private static final String DEPENDENCY = "automatic-gateway";

    private final RestClient http;
    private final CircuitBreaker breaker;

    public AutomaticGatewayHttpAdapter(@Value("${pix.automatic.gateway-url:${pix.dict-gateway.base-url}}") String baseUrl,
                                       @Value("${pix.dict-gateway.connect-timeout:500ms}") Duration connectTimeout,
                                       @Value("${pix.dict-gateway.read-timeout:3s}") Duration readTimeout, CircuitBreakerRegistry registry) {
        this.http = GatewayHttp.client(baseUrl, connectTimeout, readTimeout);
        this.breaker = registry.circuitBreaker("dict-gateway");
    }

    @Override
    public void approve(String idRec, Money payerMaxAmount) {
        Map<String, Object> body = new HashMap<>();
        if (payerMaxAmount != null) {
            body.put("payerMaxAmount", payerMaxAmount.toBcb());
        }
        post(idRec, "approval", body);
    }

    @Override
    public void reject(String idRec, String rejectionCode) {
        post(idRec, "rejection", Map.of("rejectionCode", rejectionCode));
    }

    @Override
    public void cancel(String idRec, String cancelledBy) {
        post(idRec, "cancellation", Map.of("cancelledBy", cancelledBy));
    }

    private void post(String idRec, String action, Map<String, Object> body) {
        GatewayHttp.call(breaker, DEPENDENCY, () -> http.post().uri("/spi/v1/automatic/authorizations/{idRec}/{action}", idRec, action)
                .contentType(MediaType.APPLICATION_JSON).header("X-Request-Id", UUID.randomUUID().toString()).body(body).exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    GatewayHttp.failOnTechnicalStatus(status, DEPENDENCY);
                    if (response.getStatusCode().is2xxSuccessful() || status == 409) {
                        return null;
                    }
                    if (status == 400 || status == 404 || status == 422) {
                        throw new BusinessException(ErrorCodes.BUSINESS_RULE, "O recebedor/SPI recusou a resposta à autorização.");
                    }
                    throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, DEPENDENCY + " respondeu " + status + " (contrato/URL inesperados)", null);
                }));
    }
}
