package br.com.pixmanager.api.rest.pixkey;

import br.com.pixmanager.core.port.in.pixkey.ActivatePendingKeysUseCase;
import br.com.pixmanager.support.TestDocuments;
import br.com.pixmanager.support.integration.AbstractApplicationIT;
import br.com.pixmanager.support.integration.AccountFixtures.Account;
import br.com.pixmanager.support.integration.DictGatewayStub;
import br.com.pixmanager.support.integration.TestJwt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Chaves Pix (DICT via dict-gateway): titularidade, limite por conta, ativação fora da transação, exclusão, lookup")
class PixKeyApiIT extends AbstractApplicationIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ActivatePendingKeysUseCase activate;

    String cpf;
    Account pf;

    @BeforeEach
    void setUp() {
        DictGatewayStub.reset();
        cpf = TestDocuments.randomCpf();
        pf = fixtures.account(cpf, "Titular PF");
    }

    org.springframework.test.web.servlet.request.RequestPostProcessor jwt(String... scopes) {
        return TestJwt.client("cli", List.of(pf.id()), scopes);
    }

    String register(String type, String value) {
        return "{\"accountId\":\"%s\",\"pixKeyType\":\"%s\"%s}".formatted(pf.id(), type, value == null ? "" : ",\"pixKey\":\"" + value + "\"");
    }

    JsonNode create(String type, String value) throws Exception {
        MvcResult r = mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).contentType(MediaType.APPLICATION_JSON).content(register(type, value)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("BR-KEY-02: chave CPF = CPF do titular ⇒ 201 PENDING_DICT, resposta MASCARADA, evento PixKeyRegistered; CPF de outro ⇒ 422")
    void shouldRegisterOwnCpfKeyAndMaskIt() throws Exception {
        int before = fixtures.outboxCount("PixKeyRegistered");
        mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).contentType(MediaType.APPLICATION_JSON).content(register("CPF", cpf)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_DICT"))
                .andExpect(jsonPath("$.pixKeyMasked").value(org.hamcrest.Matchers.startsWith("***.")))
                .andExpect(content().string(not(containsString(cpf))));
        assertThat(fixtures.outboxCount("PixKeyRegistered")).isEqualTo(before + 1);

        mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).contentType(MediaType.APPLICATION_JSON)
                        .content(register("CPF", TestDocuments.randomCpf())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-010"));
        mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).contentType(MediaType.APPLICATION_JSON).content(register("CPF", "11111111111")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-012"));
        // CNPJ numa conta PF não pode (BR-DOC-06)
        mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).contentType(MediaType.APPLICATION_JSON).content(register("CNPJ", "11222333000181")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-010"));
    }

    @Test
    @DisplayName("Conta PJ: chave CNPJ alfanumérico do titular é aceita")
    void shouldAcceptAlphanumericCnpjKeyOnBusinessAccount() throws Exception {
        String cnpj = TestDocuments.randomCnpj();
        Account pj = fixtures.account(cnpj, "Empresa");
        mvc.perform(post("/v1/pix-keys").with(TestJwt.client("cli", List.of(pj.id()), "pixkey.write")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"pixKeyType\":\"CNPJ\",\"pixKey\":\"%s\"}".formatted(pj.id(), cnpj)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_DICT"));
    }

    @Test
    @DisplayName("BR-KEY-03/04: limite de 5 chaves por conta PF (6ª ⇒ PIX-422-011); EMAIL/PHONE nascem aguardando prova de posse; chave repetida ⇒ 409")
    void shouldEnforceKeyLimitOwnershipProofAndUniqueness() throws Exception {
        for (int i = 0; i < 4; i++) {
            create("EVP", null);
        }
        JsonNode email = create("EMAIL", "usuario" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com.br");
        assertThat(email.get("status").asText()).isEqualTo("PENDING_OWNERSHIP_PROOF");
        mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).contentType(MediaType.APPLICATION_JSON).content(register("EVP", null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-011"));
        // limite é POR CONTA, não por pessoa: outra conta do mesmo titular não herda a contagem
        Account other = fixtures.account(TestDocuments.randomCpf(), "Outro");
        mvc.perform(post("/v1/pix-keys").with(TestJwt.client("cli", List.of(other.id()), "pixkey.write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"%s\",\"pixKeyType\":\"EVP\"}".formatted(other.id()))).andExpect(status().isCreated());
        // valor repetido
        mvc.perform(post("/v1/pix-keys").with(TestJwt.client("cli", List.of(other.id()), "pixkey.write")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"pixKeyType\":\"PHONE\",\"pixKey\":\"+5511999990001\"}".formatted(other.id())))
                .andExpect(status().isCreated());
        Account third = fixtures.account(TestDocuments.randomCpf(), "Terceiro");
        mvc.perform(post("/v1/pix-keys").with(TestJwt.client("cli", List.of(third.id()), "pixkey.write")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"pixKeyType\":\"PHONE\",\"pixKey\":\"+5511999990001\"}".formatted(third.id())))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Ativação no DICT fora da transação: 201 ⇒ ACTIVE; 4xx de negócio ⇒ REJECTED (definitivo); 5xx ⇒ continua PENDING_DICT (nunca vira REJECTED)")
    void shouldActivateRejectOrKeepPendingBySourceOfFailure() throws Exception {
        JsonNode ok = create("EVP", null);
        DictGatewayStub.registerOk("cid-1");
        activate.activatePending(50);
        assertThat(statusOf(ok.get("id").asText())).isEqualTo("ACTIVE");

        JsonNode refused = create("EVP", null);
        DictGatewayStub.reset();
        DictGatewayStub.registerStatus(422);
        activate.activatePending(50);
        assertThat(statusOf(refused.get("id").asText())).isEqualTo("REJECTED");

        JsonNode flaky = create("EVP", null);
        DictGatewayStub.reset();
        DictGatewayStub.registerStatus(500);
        activate.activatePending(50);
        assertThat(statusOf(flaky.get("id").asText())).as("falha técnica não rejeita a chave").isEqualTo("PENDING_DICT");
        DictGatewayStub.reset();
        DictGatewayStub.registerOk("cid-2");
        activate.activatePending(50);
        assertThat(statusOf(flaky.get("id").asText())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("POST /{id}/deletion: sem If-Match ⇒ 428; versão errada ⇒ 412; ok ⇒ DICT chamado e DELETED; DICT fora ⇒ chave segue ACTIVE")
    void shouldDeleteWithIfMatchAndOnlyAfterTheDictAcknowledges() throws Exception {
        JsonNode key = create("EVP", null);
        DictGatewayStub.registerOk("cid-9");
        activate.activatePending(50);
        String id = key.get("id").asText();
        String body = "{\"reason\":\"USER_REQUESTED\"}";
        long version = jdbc.sql("SELECT version FROM pix_key WHERE id = :id").param("id", UUID.fromString(id)).query(Long.class).single();

        mvc.perform(post("/v1/pix-keys/{id}/deletion", id).with(jwt("pixkey.write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post("/v1/pix-keys/{id}/deletion", id).with(jwt("pixkey.write")).header("If-Match", "\"999\"")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isPreconditionFailed());

        DictGatewayStub.deleteStatus(503);
        mvc.perform(post("/v1/pix-keys/{id}/deletion", id).with(jwt("pixkey.write")).header("If-Match", "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isServiceUnavailable());
        assertThat(statusOf(id)).isEqualTo("ACTIVE");

        DictGatewayStub.reset();
        DictGatewayStub.deleteOk();
        mvc.perform(post("/v1/pix-keys/{id}/deletion", id).with(jwt("pixkey.write")).header("If-Match", "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"));
        DictGatewayStub.SERVER.verify(postRequestedFor(urlEqualTo("/dict/v1/entries/deletion")));
    }

    @Test
    @DisplayName("Lookup (POST: a chave é PII): documento do dono MASCARADO por padrão, completo só com pii.document.read; DICT 404 ⇒ 404")
    void shouldLookupKeyWithMaskedOwnerDocument() throws Exception {
        String ownerCpf = TestDocuments.randomCpf();
        DictGatewayStub.lookupFound(ownerCpf);
        String req = "{\"payerAccountId\":\"%s\",\"pixKeyType\":\"EMAIL\",\"pixKey\":\"alguem@exemplo.com.br\"}".formatted(pf.id());
        mvc.perform(post("/v1/pix-keys/lookups").with(jwt("pixkey.read")).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ownerName").value("Joao Favorecido"))
                .andExpect(jsonPath("$.ownerDocument").value(org.hamcrest.Matchers.startsWith("***.")));
        mvc.perform(post("/v1/pix-keys/lookups").with(jwt("pixkey.read", "pii.document.read")).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(jsonPath("$.ownerDocument").value(ownerCpf));
        DictGatewayStub.reset();
        DictGatewayStub.lookupNotFound();
        mvc.perform(post("/v1/pix-keys/lookups").with(jwt("pixkey.read")).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isNotFound());
        // conta que não é do chamador ⇒ 404 (sem vazar existência)
        mvc.perform(post("/v1/pix-keys/lookups").with(TestJwt.client("outro", List.of(UUID.randomUUID()), "pixkey.read"))
                .contentType(MediaType.APPLICATION_JSON).content(req)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Idempotency-Key: replay devolve a MESMA resposta (sem 2ª chave); corpo diferente ⇒ 422; falha de negócio NÃO queima a chave")
    void shouldReplayIdempotentlyAndNotBurnTheKeyOnFailure() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        String body = register("EVP", null);
        MvcResult first = mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).get("id"))
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()).get("id"));
        assertThat(jdbc.sql("SELECT count(*) FROM pix_key WHERE account_id = :a").param("a", pf.id()).query(Integer.class).single()).isEqualTo(1);

        mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(register("EMAIL", "outro@exemplo.com.br")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-900"));

        // falha de negócio (CPF alheio) libera a chave: reenviar CORRIGIDO com a mesma chave funciona
        String key2 = "idem-" + UUID.randomUUID();
        mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).header("Idempotency-Key", key2).contentType(MediaType.APPLICATION_JSON)
                .content(register("CPF", TestDocuments.randomCpf()))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).header("Idempotency-Key", key2).contentType(MediaType.APPLICATION_JSON)
                .content(register("CPF", cpf))).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Segurança: 403 sem escopo; 404 ao ler chave de outra conta; listagem de conta alheia vem vazia")
    void shouldHideOtherAccountsKeys() throws Exception {
        JsonNode key = create("EVP", null);
        mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.read")).contentType(MediaType.APPLICATION_JSON).content(register("EVP", null)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/pix-keys/{id}", key.get("id").asText()).with(TestJwt.client("outro", List.of(UUID.randomUUID()), "pixkey.read")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/pix-keys").param("accountId", pf.id().toString()).with(TestJwt.client("outro", List.of(UUID.randomUUID()), "pixkey.read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/v1/pix-keys").param("accountId", pf.id().toString()).with(jwt("pixkey.read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(key.get("id").asText()));
    }

    String statusOf(String id) {
        return jdbc.sql("SELECT status FROM pix_key WHERE id = :id").param("id", UUID.fromString(id)).query(String.class).single();
    }
}
