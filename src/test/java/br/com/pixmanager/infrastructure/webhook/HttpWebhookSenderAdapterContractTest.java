package br.com.pixmanager.infrastructure.webhook;

import br.com.pixmanager.core.port.out.WebhookSenderPortOut.SendResult;
import br.com.pixmanager.support.FixedClock;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Envio do webhook (WireMock): assinatura verificável, sem redirecionamento, sem exceção por falha de rede")
class HttpWebhookSenderAdapterContractTest {

    static final String KEY_B64 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    static final String BODY = "{\"pix\":[{\"endToEndId\":\"E1\",\"valor\":\"10.00\"}]}";

    WireMockServer wireMock;
    FixedClock clock;
    HttpWebhookSenderAdapter sender;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort().dynamicHttpsPort().keystorePath("src/test/resources/tls/test-server.p12").keystorePassword("changeit").keyManagerPassword("changeit"));
        wireMock.start();
        clock = FixedClock.at("2026-10-16T12:00:00Z");
        sender = new HttpWebhookSenderAdapter(KEY_B64, Duration.ofMillis(500), Duration.ofMillis(600), "", clock);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private String url() {
        return "http://localhost:" + wireMock.port() + "/receptor";
    }

    @Test
    @DisplayName("POST em <url>/pix com o corpo exato e a assinatura HMAC(timestamp.corpo) que o receptor consegue recalcular")
    void postsSignedBody() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(200)));

        SendResult sendResult = sender.send(url() + "/", BODY);                       // barra final não gera "//pix"

        assertThat(sendResult.success()).isTrue();
        assertThat(sendResult.httpStatus()).isEqualTo(200);
        LoggedRequest loggedRequest = wireMock.findAll(postRequestedFor(urlEqualTo("/receptor/pix"))).get(0);
        assertThat(loggedRequest.getBodyAsString()).isEqualTo(BODY);
        String timestamp = loggedRequest.getHeader("x-pix-timestamp");
        assertThat(timestamp).isEqualTo(String.valueOf(clock.instant().getEpochSecond()));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(KEY_B64), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + BODY).getBytes(StandardCharsets.UTF_8)));
        assertThat(loggedRequest.getHeader("x-pix-signature")).isEqualTo("v1=" + expected);
        assertThat(loggedRequest.getHeader("Content-Type")).contains("application/json");
    }

    @Test
    @DisplayName("o /pix entra no caminho, antes da query; barras finais e porta são preservadas")
    void buildsTheEndpointPath() {
        assertThat(HttpWebhookSenderAdapter.pixEndpoint("https://loja.example/hook")).hasToString("https://loja.example/hook/pix");
        assertThat(HttpWebhookSenderAdapter.pixEndpoint("https://loja.example/hook///")).hasToString("https://loja.example/hook/pix");
        assertThat(HttpWebhookSenderAdapter.pixEndpoint("https://loja.example")).hasToString("https://loja.example/pix");
        assertThat(HttpWebhookSenderAdapter.pixEndpoint("https://loja.example:8443/a/b?token=x&y=1")).hasToString("https://loja.example:8443/a/b/pix?token=x&y=1");
        assertThat(HttpWebhookSenderAdapter.pixEndpoint(" https://loja.example/a%20b ")).hasToString("https://loja.example/a%20b/pix");
    }

    @Test
    @DisplayName("assinatura muda com o corpo e com o timestamp (anti-adulteração e anti-replay)")
    void signatureCoversBodyAndTimestamp() {
        String signature = sender.sign("100", BODY);
        assertThat(sender.sign("100", BODY + " ")).isNotEqualTo(signature);
        assertThat(sender.sign("101", BODY)).isNotEqualTo(signature);
        assertThat(sender.sign("100", BODY)).isEqualTo(signature).hasSize(64);
    }

    @Test
    @DisplayName("NÃO segue redirecionamento (um 302 poderia levar a um host interno): devolve 302 e falha a entrega")
    void doesNotFollowRedirects() {
        wireMock.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(302).withHeader("Location", "http://localhost:" + wireMock.port() + "/interno")));

        SendResult sendResult = sender.send(url(), BODY);

        assertThat(sendResult.httpStatus()).isEqualTo(302);
        assertThat(sendResult.success()).isFalse();
        assertThat(wireMock.findAll(anyRequestedFor(urlEqualTo("/interno")))).isEmpty();
    }

    @Test
    @DisplayName("4xx/5xx voltam como status (o serviço decide o retry); só 2xx é sucesso")
    void statusMapping() {
        for (int status : new int[]{201, 204}) {
            wireMock.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(status)));
            assertThat(sender.send(url(), BODY).success()).as("HTTP %d", status).isTrue();
        }
        for (int status : new int[]{301, 400, 404, 429, 500, 503}) {
            wireMock.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(status)));
            SendResult sendResult = sender.send(url(), BODY);
            assertThat(sendResult.success()).as("HTTP %d", status).isFalse();
            assertThat(sendResult.httpStatus()).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("timeout e conexão recusada NÃO lançam: viram resultado sem status e com a causa")
    void networkFailuresAreResults() {
        wireMock.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));
        String target = url();
        SendResult slow = sender.send(target, BODY);
        assertThat(slow.httpStatus()).isNull();
        assertThat(slow.error()).isNotBlank();

        wireMock.stop();
        SendResult refused = sender.send(target, BODY);
        assertThat(refused.httpStatus()).isNull();
        assertThat(refused.error()).isNotBlank();
    }

    @Test
    @DisplayName("a chave de assinatura é obrigatória e precisa ter ao menos 32 bytes (fail-fast)")
    void signingKeyIsRequired() {
        assertThatThrownBy(() -> new HttpWebhookSenderAdapter("", Duration.ofSeconds(1), Duration.ofSeconds(1), "", clock)).hasMessageContaining("obrigatório");
        assertThatThrownBy(() -> new HttpWebhookSenderAdapter(null, Duration.ofSeconds(1), Duration.ofSeconds(1), "", clock)).hasMessageContaining("obrigatório");
        assertThatThrownBy(() -> new HttpWebhookSenderAdapter(Base64.getEncoder().encodeToString(new byte[16]), Duration.ofSeconds(1), Duration.ofSeconds(1), "", clock))
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("HTTPS com certificado autoassinado: por padrão FALHA (valida a cadeia); confiando no certificado informado (tls-ca-path) entrega")
    void selfSignedCertificate() {
        wireMock.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(200)));
        String https = "https://localhost:" + wireMock.httpsPort() + "/receptor";

        SendResult strict = sender.send(https, BODY);
        assertThat(strict.httpStatus()).isNull();
        assertThat(strict.error()).isNotBlank();

        HttpWebhookSenderAdapter devAdapter = new HttpWebhookSenderAdapter(KEY_B64, Duration.ofSeconds(3), Duration.ofSeconds(3), "src/test/resources/tls/test-server.crt", clock);
        SendResult lenient = devAdapter.send(https, BODY);
        assertThat(lenient.error()).as("erro confiando no certificado de teste").isNull();
        assertThat(lenient.success()).isTrue();
    }
}
