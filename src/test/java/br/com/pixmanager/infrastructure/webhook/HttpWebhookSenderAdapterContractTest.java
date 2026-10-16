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

    WireMockServer wm;
    FixedClock clock;
    HttpWebhookSenderAdapter sender;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort().dynamicHttpsPort());
        wm.start();
        clock = FixedClock.at("2026-10-16T12:00:00Z");
        sender = new HttpWebhookSenderAdapter(KEY_B64, Duration.ofMillis(500), Duration.ofMillis(600), false, clock);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private String url() {
        return "http://localhost:" + wm.port() + "/receptor";
    }

    @Test
    @DisplayName("POST em <url>/pix com o corpo exato e a assinatura HMAC(timestamp.corpo) que o receptor consegue recalcular")
    void postsSignedBody() throws Exception {
        wm.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(200)));

        SendResult r = sender.send(url() + "/", BODY);                       // barra final não gera "//pix"

        assertThat(r.success()).isTrue();
        assertThat(r.httpStatus()).isEqualTo(200);
        LoggedRequest req = wm.findAll(postRequestedFor(urlEqualTo("/receptor/pix"))).get(0);
        assertThat(req.getBodyAsString()).isEqualTo(BODY);
        String timestamp = req.getHeader("x-pix-timestamp");
        assertThat(timestamp).isEqualTo(String.valueOf(clock.instant().getEpochSecond()));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(KEY_B64), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + BODY).getBytes(StandardCharsets.UTF_8)));
        assertThat(req.getHeader("x-pix-signature")).isEqualTo("v1=" + expected);
        assertThat(req.getHeader("Content-Type")).contains("application/json");
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
        String a = sender.sign("100", BODY);
        assertThat(sender.sign("100", BODY + " ")).isNotEqualTo(a);
        assertThat(sender.sign("101", BODY)).isNotEqualTo(a);
        assertThat(sender.sign("100", BODY)).isEqualTo(a).hasSize(64);
    }

    @Test
    @DisplayName("NÃO segue redirecionamento (um 302 poderia levar a um host interno): devolve 302 e falha a entrega")
    void doesNotFollowRedirects() {
        wm.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(302).withHeader("Location", "http://localhost:" + wm.port() + "/interno")));

        SendResult r = sender.send(url(), BODY);

        assertThat(r.httpStatus()).isEqualTo(302);
        assertThat(r.success()).isFalse();
        assertThat(wm.findAll(anyRequestedFor(urlEqualTo("/interno")))).isEmpty();
    }

    @Test
    @DisplayName("4xx/5xx voltam como status (o serviço decide o retry); só 2xx é sucesso")
    void statusMapping() {
        for (int status : new int[]{201, 204}) {
            wm.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(status)));
            assertThat(sender.send(url(), BODY).success()).as("HTTP %d", status).isTrue();
        }
        for (int status : new int[]{301, 400, 404, 429, 500, 503}) {
            wm.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(status)));
            SendResult r = sender.send(url(), BODY);
            assertThat(r.success()).as("HTTP %d", status).isFalse();
            assertThat(r.httpStatus()).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("timeout e conexão recusada NÃO lançam: viram resultado sem status e com a causa")
    void networkFailuresAreResults() {
        wm.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));
        String target = url();
        SendResult slow = sender.send(target, BODY);
        assertThat(slow.httpStatus()).isNull();
        assertThat(slow.error()).isNotBlank();

        wm.stop();
        SendResult refused = sender.send(target, BODY);
        assertThat(refused.httpStatus()).isNull();
        assertThat(refused.error()).isNotBlank();
    }

    @Test
    @DisplayName("a chave de assinatura é obrigatória e precisa ter ao menos 32 bytes (fail-fast)")
    void signingKeyIsRequired() {
        assertThatThrownBy(() -> new HttpWebhookSenderAdapter("", Duration.ofSeconds(1), Duration.ofSeconds(1), false, clock)).hasMessageContaining("obrigatório");
        assertThatThrownBy(() -> new HttpWebhookSenderAdapter(null, Duration.ofSeconds(1), Duration.ofSeconds(1), false, clock)).hasMessageContaining("obrigatório");
        assertThatThrownBy(() -> new HttpWebhookSenderAdapter(Base64.getEncoder().encodeToString(new byte[16]), Duration.ofSeconds(1), Duration.ofSeconds(1), false, clock))
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("HTTPS com certificado autoassinado: por padrão FALHA (valida a cadeia); com insecure-tls (dev/teste) entrega")
    void selfSignedCertificate() {
        wm.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(200)));
        String https = "https://localhost:" + wm.httpsPort() + "/receptor";

        SendResult strict = sender.send(https, BODY);
        assertThat(strict.httpStatus()).isNull();
        assertThat(strict.error()).isNotBlank();

        HttpWebhookSenderAdapter dev = new HttpWebhookSenderAdapter(KEY_B64, Duration.ofSeconds(3), Duration.ofSeconds(3), true, clock);
        SendResult lenient = dev.send(https, BODY);
        assertThat(lenient.error()).as("erro com insecure-tls").isNull();
        assertThat(lenient.success()).isTrue();
    }
}
