package br.com.pixmanager.infrastructure.otp;

import br.com.pixmanager.core.domain.enums.OtpChannel;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.DependencyUnavailableException;
import br.com.pixmanager.shared.exception.IntegrationException;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Contrato do serviço de notificação do OTP (WireMock): quem é culpa do cliente × quem é nossa configuração/infra")
class HttpOtpSenderAdapterContractTest {

    WireMockServer wm;
    HttpOtpSenderAdapter adapter;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        adapter = new HttpOtpSenderAdapter("http://localhost:" + wm.port(), Duration.ofMillis(300), Duration.ofMillis(400), CircuitBreakerRegistry.ofDefaults());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private void send() {
        adapter.send(OtpChannel.EMAIL, "maria@example.com", "123456", Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("2xx ⇒ aceito; o corpo leva canal, destino, código e validade em segundos")
    void sendsTheExpectedBody() {
        wm.stubFor(post(urlEqualTo("/otp/v1/messages")).willReturn(aResponse().withStatus(202)));

        assertThatCode(this::send).doesNotThrowAnyException();

        wm.verify(postRequestedFor(urlEqualTo("/otp/v1/messages"))
                .withRequestBody(equalToJson("{\"channel\":\"EMAIL\",\"destination\":\"maria@example.com\",\"code\":\"123456\",\"validForSeconds\":300}"))
                .withHeader("X-Request-Id", containing("-")));
    }

    @ParameterizedTest(name = "HTTP {0} ⇒ recusa de NEGÓCIO (destino inválido)")
    @ValueSource(ints = {400, 422})
    void businessRefusal(int status) {
        wm.stubFor(post(urlEqualTo("/otp/v1/messages")).willReturn(aResponse().withStatus(status)));
        assertThatThrownBy(this::send).isInstanceOf(BusinessException.class).hasMessageContaining("recusou o destino");
    }

    @ParameterizedTest(name = "HTTP {0} ⇒ falha TÉCNICA/configuração (nunca culpa do cliente)")
    @ValueSource(ints = {404, 405, 415, 500, 502})
    void technicalFailure(int status) {
        wm.stubFor(post(urlEqualTo("/otp/v1/messages")).willReturn(aResponse().withStatus(status)));
        assertThatThrownBy(this::send).isInstanceOf(IntegrationException.class);
    }

    @ParameterizedTest(name = "HTTP {0} ⇒ dependência indisponível (503 para o cliente)")
    @ValueSource(ints = {401, 403, 429, 503})
    void unavailable(int status) {
        wm.stubFor(post(urlEqualTo("/otp/v1/messages")).willReturn(aResponse().withStatus(status)));
        assertThatThrownBy(this::send).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    @DisplayName("timeout de leitura ⇒ falha técnica")
    void readTimeout() {
        wm.stubFor(post(urlEqualTo("/otp/v1/messages")).willReturn(aResponse().withStatus(202).withFixedDelay(1500)));
        assertThatThrownBy(this::send).isInstanceOf(IntegrationException.class);
    }
}
