package br.com.pixmanager.infrastructure.qr;

import br.com.pixmanager.core.port.out.DynamicQrPayloadPortOut.DynamicQrPayload;
import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut;
import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut.Verdict;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.DependencyUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HttpDynamicQrPayloadAdapter — busca e validação do JWS do QR dinâmico")
class HttpDynamicQrPayloadAdapterTest {

    private static final char[] PW = "changeit".toCharArray();
    private static final String COB = "{\"txid\":\"abc123\",\"status\":\"ATIVA\",\"chave\":\"123e4567-e89b-42d3-a456-426655440000\","
            + "\"calendario\":{\"criacao\":\"2026-10-16T12:00:00Z\",\"expiracao\":3600},\"valor\":{\"original\":\"25.00\"}}";

    @TempDir Path tmp;
    WireMockServer server;
    Path trustStore;
    KeyStore leaf;
    KeyStore other;
    final WebhookUrlPolicyPortOut allowAll = url -> new Verdict.Allowed();

    @BeforeEach
    void setUp() throws Exception {
        server = new WireMockServer(options().dynamicPort().dynamicHttpsPort().keystorePath("src/test/resources/tls/test-server.p12").keystorePassword("changeit").keyManagerPassword("changeit"));
        server.start();
        leaf = p12("/qr/leaf.p12");
        other = p12("/qr/outro.p12");
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        try (InputStream in = getClass().getResourceAsStream("/qr/ca.crt")) {
            keyStore.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        trustStore = tmp.resolve("trust.p12");
        try (var out = new FileOutputStream(trustStore.toFile())) {
            keyStore.store(out, PW);
        }
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    @DisplayName("JWS RS256 com cadeia confiável ⇒ devolve txid, chave, valor, status e validade (criação + expiração)")
    void shouldAcceptAValidSignedPayload() throws Exception {
        DynamicQrPayload dynamicQrPayload = adapter(true, false).validate(sign(leaf, JWSAlgorithm.RS256, COB));

        assertThat(dynamicQrPayload.txid()).isEqualTo("abc123");
        assertThat(dynamicQrPayload.pixKey()).isEqualTo("123e4567-e89b-42d3-a456-426655440000");
        assertThat(dynamicQrPayload.amount().toBcb()).isEqualTo("25.00");
        assertThat(dynamicQrPayload.status()).isEqualTo("ATIVA");
        assertThat(dynamicQrPayload.expiresAt()).isEqualTo(Instant.parse("2026-10-16T13:00:00Z"));
    }

    @Test
    @DisplayName("cobv: usa valor.final se houver e vale até o fim do dia de vencimento + validadeAposVencimento (Brasília)")
    void shouldReadDueDateCharges() throws Exception {
        String cobv = "{\"txid\":\"v1\",\"status\":\"ATIVA\",\"chave\":\"a@b.com\",\"calendario\":{\"dataDeVencimento\":\"2026-10-20\","
                + "\"validadeAposVencimento\":2},\"valor\":{\"original\":\"100.00\",\"final\":\"103.50\"}}";

        DynamicQrPayload dynamicQrPayload = adapter(true, false).validate(sign(leaf, JWSAlgorithm.RS256, cobv));

        assertThat(dynamicQrPayload.amount().toBcb()).isEqualTo("103.50");
        assertThat(dynamicQrPayload.expiresAt()).isEqualTo(Instant.parse("2026-10-23T03:00:00Z"));      // 23/10 00:00 em Brasília (UTC-3)
    }

    @Test
    @DisplayName("Payload adulterado depois de assinado ⇒ PIX-422-018")
    void shouldRejectATamperedPayload() throws Exception {
        String[] parts = sign(leaf, JWSAlgorithm.RS256, COB).split("\\.");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(COB.replace("25.00", "0.01").getBytes());

        assertInvalid(() -> adapter(true, false).validate(parts[0] + "." + forged + "." + parts[2]), "Assinatura");
    }

    @Test
    @DisplayName("Certificado de quem NÃO está na trust store ⇒ recusa (cadeia não confiável), mesmo com assinatura correta")
    void shouldRejectAnUntrustedChain() throws Exception {
        assertInvalid(() -> adapter(true, false).validate(sign(other, JWSAlgorithm.RS256, COB)), "não é confiável");
    }

    @Test
    @DisplayName("Sem trust store configurada o adaptador FALHA FECHADO; com allow-unverified-chain (dev) aceita só a assinatura")
    void shouldFailClosedWithoutATrustStore() throws Exception {
        assertInvalid(() -> adapter(false, false).validate(sign(leaf, JWSAlgorithm.RS256, COB)), "trust store");
        assertThat(adapter(false, true).validate(sign(leaf, JWSAlgorithm.RS256, COB)).txid()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("Algoritmo simétrico (HS256), JWS sem x5c e lixo ⇒ recusa")
    void shouldRejectWeakAlgorithmsMissingCertsAndGarbage() throws Exception {
        JWSObject jwsObject = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), new Payload(COB));
        jwsObject.sign(new MACSigner(new byte[32]));
        assertInvalid(() -> adapter(true, false).validate(jwsObject.serialize()), "Algoritmo");

        JWSObject noX5c = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), new Payload(COB));
        noX5c.sign(new RSASSASigner((PrivateKey) leaf.getKey("leaf", PW)));
        assertInvalid(() -> adapter(true, false).validate(noX5c.serialize()), "x5c");

        assertInvalid(() -> adapter(true, false).validate("isto não é um jws"), "JWS");
    }

    @Test
    @DisplayName("Payload sem campos essenciais, com valor zero ou ilegível ⇒ recusa")
    void shouldRejectIncompletePayloads() {
        assertInvalid(() -> HttpDynamicQrPayloadAdapter.parsePayload("{\"txid\":\"x\"}"), "não traz");
        assertInvalid(() -> HttpDynamicQrPayloadAdapter.parsePayload("{\"txid\":\"x\",\"status\":\"ATIVA\",\"chave\":\"k\",\"valor\":{\"original\":\"0.00\"}}"), "maior que zero");
        assertInvalid(() -> HttpDynamicQrPayloadAdapter.parsePayload("não é json"), "ilegível");
    }

    @Test
    @DisplayName("fetch: baixa por HTTPS, valida e devolve; 404 ⇒ recusa; 5xx/timeout ⇒ dependência indisponível; 302 e corpo gigante ⇒ recusa")
    void shouldFetchOverHttps() throws Exception {
        String url = "localhost:" + server.httpsPort() + "/qr/v2/abc";
        server.stubFor(get(urlEqualTo("/qr/v2/abc")).willReturn(aResponse().withStatus(200).withBody(sign(leaf, JWSAlgorithm.RS256, COB))));
        server.stubFor(get(urlEqualTo("/qr/v2/nao")).willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlEqualTo("/qr/v2/erro")).willReturn(aResponse().withStatus(503)));
        server.stubFor(get(urlEqualTo("/qr/v2/redir")).willReturn(aResponse().withStatus(302).withHeader("Location", "https://169.254.169.254/")));
        server.stubFor(get(urlEqualTo("/qr/v2/grande")).willReturn(aResponse().withStatus(200).withBody("x".repeat(70_000))));
        server.stubFor(get(urlEqualTo("/qr/v2/lento")).willReturn(aResponse().withStatus(200).withFixedDelay(2500).withBody("x")));
        var a = adapter(true, false);

        assertThat(a.fetch(url).txid()).isEqualTo("abc123");
        assertInvalid(() -> a.fetch("localhost:" + server.httpsPort() + "/qr/v2/nao"), "não encontrada");
        assertThatThrownBy(() -> a.fetch("localhost:" + server.httpsPort() + "/qr/v2/erro")).isInstanceOf(DependencyUnavailableException.class);
        assertInvalid(() -> a.fetch("localhost:" + server.httpsPort() + "/qr/v2/redir"), "recusou");
        assertInvalid(() -> a.fetch("localhost:" + server.httpsPort() + "/qr/v2/grande"), "tamanho");
        assertThatThrownBy(() -> a.fetch("localhost:" + server.httpsPort() + "/qr/v2/lento")).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    @DisplayName("fetch: URL com esquema ou recusada pela política anti-SSRF nunca gera requisição")
    void shouldNotCallForbiddenUrls() {
        var denying = new HttpDynamicQrPayloadAdapter(url -> new Verdict.Forbidden("host interno"), Duration.ofSeconds(1), Duration.ofSeconds(1),
                65_536, "", "", "PKCS12", true, "src/test/resources/tls/test-server.crt");

        assertInvalid(() -> denying.fetch("http://x.example/qr"), "esquema");
        assertInvalid(() -> denying.fetch("169.254.169.254/latest"), "recusada");
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private HttpDynamicQrPayloadAdapter adapter(boolean withTrustStore, boolean allowUnverified) {
        return new HttpDynamicQrPayloadAdapter(allowAll, Duration.ofSeconds(2), Duration.ofSeconds(2), 65_536,
                withTrustStore ? trustStore.toString() : "", withTrustStore ? "changeit" : "", "PKCS12", allowUnverified, "src/test/resources/tls/test-server.crt");
    }

    private static String sign(KeyStore store, JWSAlgorithm alg, String json) throws Exception {
        List<Base64> chain = new ArrayList<>();
        for (Certificate certificate : store.getCertificateChain(store.aliases().nextElement())) {
            chain.add(Base64.encode(((X509Certificate) certificate).getEncoded()));
        }
        JWSObject jws = new JWSObject(new JWSHeader.Builder(alg).x509CertChain(chain).build(), new Payload(json));
        jws.sign(new RSASSASigner((PrivateKey) store.getKey(store.aliases().nextElement(), PW)));
        return jws.serialize();
    }

    private KeyStore p12(String resource) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            keyStore.load(in, PW);
        }
        return keyStore;
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messagePart) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(ErrorCodes.QR_DYNAMIC_INVALID);
            assertThat(exception.getMessage()).contains(messagePart);
        });
    }
}
