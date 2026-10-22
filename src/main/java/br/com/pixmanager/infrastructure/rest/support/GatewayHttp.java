package br.com.pixmanager.infrastructure.rest.support;

import br.com.pixmanager.shared.exception.DependencyUnavailableException;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.IntegrationException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Peças comuns dos clientes de saída (dict-gateway, core, webhook): timeouts de connect e read SEPARADOS e explícitos,
 * circuit breaker por dependência e a tradução técnica dos status (429/503 ⇒ indisponível; 5xx/IO ⇒ falha técnica).
 * 4xx definitivo NÃO passa por aqui: é resposta de negócio e cada adaptador a traduz.
 */
public final class GatewayHttp {

    private GatewayHttp() {}

    public static RestClient client(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Executa sob circuit breaker; circuito aberto e timeout/IO viram exceções técnicas tipadas. */
    public static <T> T call(CircuitBreaker breaker, String dependency, Supplier<T> action) {
        try {
            return breaker.executeSupplier(action);
        } catch (CallNotPermittedException exception) {
            throw new DependencyUnavailableException("Circuito de " + dependency + " aberto", exception);
        } catch (ResourceAccessException exception) {
            throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, dependency + " indisponível (timeout/IO)", exception);
        }
    }

    /**
     * Status que NÃO são resposta de negócio: 429/503 (sobrecarga) e 401/403/408/425 (credencial/configuração/timeout do
     * gateway — o chamador não pode tratá-los como recusa definitiva, senão uma credencial expirada rejeita tudo).
     */
    public static boolean isUnavailableStatus(int status) {
        return status == 429 || status == 503 || status == 401 || status == 403 || status == 408 || status == 425;
    }

    /** 429/503/401/403/408/425 ⇒ {@link DependencyUnavailableException}; demais 5xx ⇒ {@link IntegrationException}; senão não faz nada. */
    public static void failOnTechnicalStatus(int status, String dependency) {
        if (isUnavailableStatus(status)) {
            throw new DependencyUnavailableException(dependency + " respondeu " + status, null);
        }
        if (status >= 500) {
            throw new IntegrationException(ErrorCodes.DEPENDENCY_FAILURE, dependency + " respondeu " + status, null);
        }
    }
}
