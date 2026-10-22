package br.com.pixmanager.api.rest.webhook;

import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.ValueComponent;
import br.com.pixmanager.core.domain.enums.ComponentType;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase.ReceivePixCommand;
import br.com.pixmanager.core.port.in.webhook.DispatchWebhooksUseCase;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import br.com.pixmanager.support.TestDocuments;
import br.com.pixmanager.support.integration.AbstractApplicationIT;
import br.com.pixmanager.support.integration.AccountFixtures.Account;
import br.com.pixmanager.support.integration.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Webhook de Pix recebido: cadastro BCB, notificação na transação do Pix, entrega assinada, retry/DEAD e reenvio")
class WebhookApiIT extends AbstractApplicationIT {

    static WireMockServer receiver;

    @BeforeAll
    static void startReceiver() {
        receiver = new WireMockServer(options().dynamicPort().dynamicHttpsPort().keystorePath("src/test/resources/tls/test-server.p12").keystorePassword("changeit").keyManagerPassword("changeit"));
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ReceivePixUseCase receivePix;
    @Autowired DispatchWebhooksUseCase dispatch;
    @Autowired CryptoService crypto;
    @Value("${pix.webhook.signing-key-base64}") String signingKey;

    Account account;
    String pixKey;
    String baseUrl;

    @BeforeEach
    void setUp() {
        receiver.resetAll();
        account = fixtures.account(TestDocuments.randomCpf(), "Recebedor");
        pixKey = UUID.randomUUID().toString();
        fixtures.activeKey(account.id(), "EVP", pixKey);
        baseUrl = "https://localhost:" + receiver.httpsPort() + "/receptor";
        receiver.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(200)));
    }

    org.springframework.test.web.servlet.request.RequestPostProcessor jwt(String... scopes) {
        return TestJwt.client("cli", List.of(account.id()), scopes);
    }

    org.springframework.test.web.servlet.ResultActions putWebhook(String url) throws Exception {
        return mvc.perform(put("/api/pix/v2/webhook/{chave}", pixKey).with(jwt("webhook.write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"webhookUrl\":\"%s\"}".formatted(url)));
    }

    String e2e() {
        return "E12345678202610161200" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    ReceivePixCommand pixCommand(String e2eId, String txid, String keyHash) {
        return new ReceivePixCommand("it-group", "msg-" + e2eId, "pix.spi.payment-settled.v1", 0, 1, e2eId, txid, Money.of("42.50"), Instant.now(),
                "12345678", "0001", account.number(), "CACC", "11144477735", "Maria Pagadora", "87654321", "0002", "99887", "CACC", "pedido 7",
                List.of(new ValueComponent(ComponentType.ORIGINAL, Money.of("42.50"), null, null)), keyHash);
    }

    String createCharge() throws Exception {
        String txid = UUID.randomUUID().toString().replace("-", "");
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(jwt("cob.write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"calendario\":{\"expiracao\":3600},\"valor\":{\"original\":\"42.50\"},\"chave\":\"%s\"}".formatted(pixKey))).andExpect(status().isCreated());
        return txid;
    }

    JsonNode onlyDelivery(String e2eId) {
        var rows = jdbc.sql("SELECT d.id, d.status, d.attempts, d.last_http_status, d.last_error, d.payload::text AS payload FROM webhook_delivery d "
                + "JOIN pix_received r ON r.id = d.source_event_id WHERE r.end_to_end_id = :e").param("e", e2eId)
                .query((resultSet, rowNumber) -> json.createObjectNode().put("id", resultSet.getString("id")).put("status", resultSet.getString("status")).put("attempts", resultSet.getInt("attempts"))
                        .put("lastHttp", resultSet.getInt("last_http_status")).put("lastError", resultSet.getString("last_error")).put("payload", resultSet.getString("payload"))).list();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    // ---------------------------------------------------------------- cadastro
    @Test
    @DisplayName("PUT cria (201) e atualiza (200); GET devolve webhookUrl/chave/criacao; DELETE 204 e depois GET 404; a lista traz só o que é do chamador")
    void shouldManageTheWebhook() throws Exception {
        putWebhook(baseUrl).andExpect(status().isCreated()).andExpect(jsonPath("$.webhookUrl").value(baseUrl)).andExpect(jsonPath("$.chave").value(pixKey))
                .andExpect(jsonPath("$.criacao").exists());
        putWebhook(baseUrl + "/v2").andExpect(status().isOk()).andExpect(jsonPath("$.webhookUrl").value(baseUrl + "/v2"));

        mvc.perform(get("/api/pix/v2/webhook/{chave}", pixKey).with(jwt("webhook.read"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookUrl").value(baseUrl + "/v2")).andExpect(jsonPath("$.chave").value(pixKey));
        mvc.perform(get("/api/pix/v2/webhook").with(jwt("webhook.read")).param("inicio", Instant.now().minusSeconds(3600).toString())
                        .param("fim", Instant.now().plusSeconds(3600).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.webhooks.length()").value(1)).andExpect(jsonPath("$.webhooks[0].chave").value(pixKey))
                .andExpect(jsonPath("$.parametros.paginacao.quantidadeTotalDeItens").value(1));

        mvc.perform(delete("/api/pix/v2/webhook/{chave}", pixKey).with(jwt("webhook.write"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/pix/v2/webhook/{chave}", pixKey).with(jwt("webhook.read"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/pix/v2/webhook/{chave}", pixKey).with(jwt("webhook.write"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Fronteiras: sem token 401; escopo errado 403; chave de outra conta 404; URL ftp/credencial/fragmento 422 PIX-422-017; corpo vazio 400; inicio>fim 400")
    void shouldGuardTheBoundaries() throws Exception {
        mvc.perform(put("/api/pix/v2/webhook/{chave}", pixKey).contentType(MediaType.APPLICATION_JSON).content("{\"webhookUrl\":\"" + baseUrl + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/pix/v2/webhook/{chave}", pixKey).with(jwt("webhook.read")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"webhookUrl\":\"" + baseUrl + "\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/pix/v2/webhook/{chave}", pixKey).with(TestJwt.client("outro", List.of(UUID.randomUUID()), "webhook.write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"webhookUrl\":\"" + baseUrl + "\"}")).andExpect(status().isNotFound());
        for (String bad : new String[]{"ftp://loja.example/hook", "https://user:senha@loja.example/hook", "https://loja.example/hook#x"}) {
            putWebhook(bad).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-017"));
        }
        mvc.perform(put("/api/pix/v2/webhook/{chave}", pixKey).with(jwt("webhook.write")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/pix/v2/webhook").with(jwt("webhook.read")).param("inicio", Instant.now().toString())
                .param("fim", Instant.now().minusSeconds(60).toString())).andExpect(status().isBadRequest());
        putWebhook(baseUrl).andExpect(status().isCreated());                      // a chave de outro titular nunca foi afetada
    }

    // ---------------------------------------------------------------- notificação + entrega
    @Test
    @DisplayName("Pix que quita uma cobrança da chave ⇒ entrega criada NA transação do Pix ⇒ POST <url>/pix assinado (HMAC verificável) com o corpo da API Pix ⇒ DELIVERED")
    void shouldDeliverASignedNotificationForAChargePayment() throws Exception {
        putWebhook(baseUrl).andExpect(status().isCreated());
        String txid = createCharge();
        String e2eId = e2e();

        receivePix.receive(pixCommand(e2eId, txid, null));

        assertThat(onlyDelivery(e2eId).get("status").asText()).isEqualTo("PENDING");
        assertThat(dispatch.dispatchDue(50)).isGreaterThanOrEqualTo(1);

        LoggedRequest req = receiver.findAll(postRequestedFor(urlEqualTo("/receptor/pix"))).stream()
                .filter(request -> request.getBodyAsString().contains(e2eId)).findFirst().orElseThrow();
        JsonNode body = json.readTree(req.getBodyAsString());
        JsonNode item = body.get("pix").get(0);
        assertThat(item.get("endToEndId").asText()).isEqualTo(e2eId);
        assertThat(item.get("txid").asText()).isEqualTo(txid);
        assertThat(item.get("chave").asText()).isEqualTo(pixKey);
        assertThat(item.get("valor").asText()).isEqualTo("42.50");
        assertThat(item.get("infoPagador").asText()).isEqualTo("pedido 7");
        assertThat(req.getBodyAsString()).doesNotContain("Maria Pagadora").doesNotContain("11144477735");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(signingKey), "HmacSHA256"));
        String timestamp = req.getHeader("x-pix-timestamp");
        assertThat(req.getHeader("x-pix-signature")).isEqualTo("v1=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + req.getBodyAsString()).getBytes(StandardCharsets.UTF_8))));
        assertThat(onlyDelivery(e2eId).get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("Pix sem cobrança, identificado só pelo índice cego da chave (receiverKeyHash) ⇒ também notifica; mesmo Pix reprocessado ⇒ uma entrega só")
    void shouldNotifyByKeyHashAndNeverDuplicate() throws Exception {
        putWebhook(baseUrl).andExpect(status().isCreated());
        String e2eId = e2e();
        String keyHash = crypto.blindIndex("key:" + pixKey);

        receivePix.receive(pixCommand(e2eId, null, keyHash));
        receivePix.receive(pixCommand(e2eId, null, keyHash));                      // mesma mensagem outra vez (inbox/e2eId)

        assertThat(onlyDelivery(e2eId).get("payload").asText()).doesNotContain("txid");
        dispatch.dispatchDue(50);
        assertThat(onlyDelivery(e2eId).get("status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("Sem webhook cadastrado, com webhook removido ou Pix sem chave identificável ⇒ nenhuma entrega (e o Pix é creditado normalmente)")
    void shouldNotNotifyWithoutASubscription() throws Exception {
        String semWebhook = e2e();
        receivePix.receive(pixCommand(semWebhook, null, crypto.blindIndex("key:" + pixKey)));
        assertThat(jdbc.sql("SELECT count(*) FROM pix_received WHERE end_to_end_id = :e").param("e", semWebhook).query(Integer.class).single()).isEqualTo(1);

        putWebhook(baseUrl).andExpect(status().isCreated());
        mvc.perform(delete("/api/pix/v2/webhook/{chave}", pixKey).with(jwt("webhook.write"))).andExpect(status().isNoContent());
        String removido = e2e();
        receivePix.receive(pixCommand(removido, null, crypto.blindIndex("key:" + pixKey)));

        putWebhook(baseUrl).andExpect(status().isCreated());
        String semChave = e2e();
        receivePix.receive(pixCommand(semChave, null, null));                       // sem hash e sem cobrança
        for (String endToEndId : new String[]{semWebhook, removido, semChave}) {
            assertThat(jdbc.sql("SELECT count(*) FROM webhook_delivery d JOIN pix_received r ON r.id = d.source_event_id WHERE r.end_to_end_id = :e")
                    .param("e", endToEndId).query(Integer.class).single()).as(endToEndId).isZero();
        }
    }

    @Test
    @DisplayName("Receptor fora do ar ⇒ RETRYING com backoff (guarda status e motivo); esgotadas as tentativas ⇒ DEAD; reenvio pelo back-office ⇒ PENDING ⇒ DELIVERED")
    void shouldRetryDieAndBeResent() throws Exception {
        receiver.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(503)));
        putWebhook(baseUrl).andExpect(status().isCreated());
        String e2eId = e2e();
        receivePix.receive(pixCommand(e2eId, null, crypto.blindIndex("key:" + pixKey)));
        UUID id = UUID.fromString(onlyDelivery(e2eId).get("id").asText());

        dispatch.dispatchDue(50);
        JsonNode retrying = onlyDelivery(e2eId);
        assertThat(retrying.get("status").asText()).isEqualTo("RETRYING");
        assertThat(retrying.get("attempts").asInt()).isEqualTo(1);
        assertThat(retrying.get("lastHttp").asInt()).isEqualTo(503);
        assertThat(dispatch.dispatchDue(50)).isZero();                              // a próxima tentativa é no futuro (backoff)

        jdbc.sql("UPDATE webhook_delivery SET status = 'DEAD', attempts = 8, next_attempt_at = now() WHERE id = :id").param("id", id).update();
        mvc.perform(post("/v1/webhook-deliveries/{id}/resend", id).with(jwt("webhook.write"))).andExpect(status().isForbidden());     // escopo do parceiro não reenvia
        mvc.perform(post("/v1/webhook-deliveries/{id}/resend", id).with(jwt("webhook.admin"))).andExpect(status().isForbidden());     // escopo certo, mas conta restrita (não é back-office)
        receiver.stubFor(post(urlEqualTo("/receptor/pix")).willReturn(aResponse().withStatus(200)));
        mvc.perform(post("/v1/webhook-deliveries/{id}/resend", id).with(TestJwt.admin("backoffice", "webhook.admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.attempts").value(0));
        dispatch.dispatchDue(50);
        assertThat(onlyDelivery(e2eId).get("status").asText()).isEqualTo("DELIVERED");
        mvc.perform(post("/v1/webhook-deliveries/{id}/resend", id).with(TestJwt.admin("backoffice", "webhook.admin"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/v1/webhook-deliveries/{id}/resend", UUID.randomUUID()).with(TestJwt.admin("backoffice", "webhook.admin"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Remover o webhook apaga também as entregas pendentes (nada é enviado a uma URL que o cliente retirou)")
    void deletingTheWebhookDropsPendingDeliveries() throws Exception {
        putWebhook(baseUrl).andExpect(status().isCreated());
        String e2eId = e2e();
        receivePix.receive(pixCommand(e2eId, null, crypto.blindIndex("key:" + pixKey)));
        assertThat(onlyDelivery(e2eId).get("status").asText()).isEqualTo("PENDING");

        mvc.perform(delete("/api/pix/v2/webhook/{chave}", pixKey).with(jwt("webhook.write"))).andExpect(status().isNoContent());
        dispatch.dispatchDue(50);

        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/receptor/pix"))).stream().anyMatch(request -> request.getBodyAsString().contains(e2eId))).isFalse();
    }
}
