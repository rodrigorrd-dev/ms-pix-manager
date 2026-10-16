package br.com.pixmanager.infrastructure.otp;

import br.com.pixmanager.core.domain.enums.OtpChannel;
import br.com.pixmanager.core.port.out.OtpSenderPortOut;
import br.com.pixmanager.infrastructure.rest.support.GatewayHttp;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.IntegrationException;
import br.com.pixmanager.shared.constant.ErrorCodes;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Entrega via serviço de notificação EXTERNO ({@code pix.otp.sender=http}). Contrato (a confirmar com o dono do serviço):
 * {@code POST /otp/v1/messages} com {@code {channel: SMS|EMAIL, destination, code, validForSeconds}}; 2xx = aceito, 400/422 = recusa definitiva
 * (destino inválido), qualquer outro status/timeout = falha técnica. O código só trafega neste corpo (TLS obrigatório fora do dev) e nunca é logado aqui.
 */
@Component
@ConditionalOnProperty(prefix = "pix.otp", name = "sender", havingValue = "http")
public class HttpOtpSenderAdapter implements OtpSenderPortOut {

    private static final String DEPENDENCY = "otp-gateway";

    private final RestClient http;
    private final CircuitBreaker breaker;

    public HttpOtpSenderAdapter(@Value("${pix.otp.gateway-url}") String baseUrl,
                                @Value("${pix.otp.connect-timeout:500ms}") Duration connectTimeout,
                                @Value("${pix.otp.read-timeout:3s}") Duration readTimeout, CircuitBreakerRegistry registry) {
        this.http = GatewayHttp.client(baseUrl, connectTimeout, readTimeout);
        this.breaker = registry.circuitBreaker("otp-gateway");
    }

    @Override
    public void send(OtpChannel channel, String destination, String code, Duration validFor) {
        Map<String, Object> body = Map.of("channel", channel.name(), "destination", destination, "code", code,
                "validForSeconds", validFor.toSeconds());
        GatewayHttp.call(breaker, DEPENDENCY, () -> http.post().uri("/otp/v1/messages").contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", UUID.randomUUID().toString()).body(body)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    GatewayHttp.failOnTechnicalStatus(status, DEPENDENCY);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return null;
                    }
                    if (status == 400 || status == 422) {      // única recusa DE NEGÓCIO: destino (e-mail/telefone) inválido
                        throw new BusinessException(ErrorCodes.INVALID_PIX_KEY, "O serviço de notificação recusou o destino informado.");
                    }
                    // 404/405/415...: URL ou contrato errado = problema de CONFIGURAÇÃO, nunca culpa do cliente (e é retentável após corrigir)
                    throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, DEPENDENCY + " respondeu " + status + " (contrato/URL inesperados)", null);
                }));
    }
}
