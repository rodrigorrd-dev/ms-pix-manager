package br.com.pixmanager.api.rest.charge;

import br.com.pixmanager.support.TestDocuments;
import br.com.pixmanager.support.integration.AbstractApplicationIT;
import br.com.pixmanager.support.integration.AccountFixtures.Account;
import br.com.pixmanager.support.integration.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("API Pix v2 — cob (cobrança imediata): contrato BCB, escopos, posse, idempotência do txid, outbox")
class ChargeApiIT extends AbstractApplicationIT {

    static final String DEBTOR_CPF = "11144477735";

    @Autowired MockMvc mvc;

    Account account;
    String pixKey;

    @BeforeEach
    void seed() {
        account = fixtures.account(TestDocuments.randomCpf(), "Recebedor PF");
        pixKey = UUID.randomUUID().toString();                                  // EVP
        fixtures.activeKey(account.id(), "EVP", pixKey);
    }

    String txid() {
        return UUID.randomUUID().toString().replace("-", "");                    // 32 chars [a-f0-9]
    }

    String body(String amount) {
        return """
                {"calendario":{"expiracao":3600},
                 "devedor":{"cpf":"%s","nome":"Maria da Silva"},
                 "valor":{"original":"%s"},
                 "chave":"%s",
                 "solicitacaoPagador":"Pedido 1042",
                 "infoAdicionais":[{"nome":"Pedido","valor":"1042"}]}
                """.formatted(DEBTOR_CPF, amount, pixKey);
    }

    @Test
    @DisplayName("PUT /cob/{txid} → 201 ATIVA, CPF do devedor MASCARADO, ETag, loc e evento ChargeCreated na outbox")
    void shouldCreateChargeMaskDebtorAndWriteOutbox() throws Exception {
        String txid = txid();
        int before = fixtures.outboxCount("ChargeCreated");

        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(TestJwt.client("cli", List.of(account.id()), "cob.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("123.45")))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.txid").value(txid))
                .andExpect(jsonPath("$.status").value("ATIVA"))
                .andExpect(jsonPath("$.revisao").value(0))
                .andExpect(jsonPath("$.valor.original").value("123.45"))
                .andExpect(jsonPath("$.chave").value(pixKey))
                .andExpect(jsonPath("$.devedor.cpf").value("***.444.777-**"))
                .andExpect(content().string(not(containsString(DEBTOR_CPF))))
                .andExpect(jsonPath("$.calendario.expiracao").value(3600))
                .andExpect(jsonPath("$.loc.id").isNumber())
                .andExpect(jsonPath("$.loc.tipoCob").value("cob"))
                .andExpect(jsonPath("$.infoAdicionais[0].nome").value("Pedido"));

        assertThat(fixtures.outboxCount("ChargeCreated")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("BR-COB-01: PUT repetido com o MESMO corpo → 200 (mesmo recurso); corpo DIFERENTE → 409 PIX-409-020")
    void putShouldBeIdempotentByTxid() throws Exception {
        String txid = txid();
        var jwt = TestJwt.client("cli", List.of(account.id()), "cob.write");
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(jwt).contentType(MediaType.APPLICATION_JSON).content(body("10.00")))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(jwt).contentType(MediaType.APPLICATION_JSON).content(body("10.00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.txid").value(txid));
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(jwt).contentType(MediaType.APPLICATION_JSON).content(body("99.00")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PIX-409-020"));
    }

    @Test
    @DisplayName("401 sem token; 403 sem escopo cob.write; 404 para recebedor de OUTRA conta (não vaza existência)")
    void shouldEnforceAuthenticationScopeAndOwnership() throws Exception {
        String txid = txid();
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).contentType(MediaType.APPLICATION_JSON).content(body("1.00")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("PIX-401-001"));
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(TestJwt.client("cli", List.of(account.id()), "cob.read"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("1.00")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PIX-403-001"));
        // chave de outra conta: indistinguível de chave inexistente
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(TestJwt.client("outro", List.of(UUID.randomUUID()), "cob.write"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("1.00")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-021"));
        // cobrança existente de outro recebedor: 404
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(TestJwt.client("cli", List.of(account.id()), "cob.write"))
                .contentType(MediaType.APPLICATION_JSON).content(body("1.00"))).andExpect(status().isCreated());
        mvc.perform(get("/api/pix/v2/cob/{txid}", txid).with(TestJwt.client("outro", List.of(UUID.randomUUID()), "cob.read")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Validação: txid curto → 400; CPF inválido → 422 sem eco; valor mal formatado → 400; cpf e cnpj juntos → 400")
    void shouldRejectInvalidInput() throws Exception {
        var jwt = TestJwt.client("cli", List.of(account.id()), "cob.write");
        mvc.perform(put("/api/pix/v2/cob/{txid}", "curto").with(jwt).contentType(MediaType.APPLICATION_JSON).content(body("1.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PIX-400-001"));
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid()).with(jwt).contentType(MediaType.APPLICATION_JSON)
                        .content(body("1.00").replace(DEBTOR_CPF, "11111111111")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-001"))
                .andExpect(content().string(not(containsString("11111111111"))));
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid()).with(jwt).contentType(MediaType.APPLICATION_JSON).content(body("1,00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").exists());
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid()).with(jwt).contentType(MediaType.APPLICATION_JSON)
                        .content(body("1.00").replace("\"cpf\":\"" + DEBTOR_CPF + "\"", "\"cpf\":\"" + DEBTOR_CPF + "\",\"cnpj\":\"11222333000181\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /cob/{txid} → 200 + ETag; If-None-Match igual → 304")
    void shouldGetAndHonorIfNoneMatch() throws Exception {
        String txid = txid();
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(TestJwt.client("cli", List.of(account.id()), "cob.write"))
                .contentType(MediaType.APPLICATION_JSON).content(body("5.00"))).andExpect(status().isCreated());
        var reader = TestJwt.client("cli", List.of(account.id()), "cob.read");
        mvc.perform(get("/api/pix/v2/cob/{txid}", txid).with(reader)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""));
        mvc.perform(get("/api/pix/v2/cob/{txid}", txid).with(reader).header("If-None-Match", "\"0\"")).andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("PATCH exige If-Match: ausente → 428; desatualizado → 412; malformado → 400; certo → 200 e revisao+1")
    void shouldRequireIfMatchOnRevision() throws Exception {
        String txid = txid();
        var jwt = TestJwt.client("cli", List.of(account.id()), "cob.write");
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(jwt).contentType(MediaType.APPLICATION_JSON).content(body("5.00")))
                .andExpect(status().isCreated());
        String revision = "{\"valor\":{\"original\":\"7.50\"}}";
        mvc.perform(patch("/api/pix/v2/cob/{txid}", txid).with(jwt).contentType(MediaType.APPLICATION_JSON).content(revision))
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PIX-428-001"));
        mvc.perform(patch("/api/pix/v2/cob/{txid}", txid).with(jwt).header("If-Match", "\"99\"")
                        .contentType(MediaType.APPLICATION_JSON).content(revision))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PIX-412-001"));
        mvc.perform(patch("/api/pix/v2/cob/{txid}", txid).with(jwt).header("If-Match", "abc")
                        .contentType(MediaType.APPLICATION_JSON).content(revision))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PIX-400-002"));
        mvc.perform(patch("/api/pix/v2/cob/{txid}", txid).with(jwt).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(revision))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revisao").value(1)).andExpect(jsonPath("$.valor.original").value("7.50"))
                .andExpect(header().string("ETag", "\"1\""));
        // remoção pelo recebedor
        mvc.perform(patch("/api/pix/v2/cob/{txid}", txid).with(jwt).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REMOVIDA_PELO_USUARIO_RECEBEDOR\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REMOVIDA_PELO_USUARIO_RECEBEDOR"));
        // cobrança removida não aceita nova revisão (a remoção levou a versão para 2)
        mvc.perform(patch("/api/pix/v2/cob/{txid}", txid).with(jwt).header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON).content(revision))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("GET /cob?inicio&fim → formato oficial parametros.paginacao e só as cobranças das contas do chamador")
    void shouldSearchWithOfficialPaginationFormat() throws Exception {
        var jwt = TestJwt.client("cli", List.of(account.id()), "cob.write", "cob.read");
        for (int i = 0; i < 3; i++) {
            mvc.perform(put("/api/pix/v2/cob/{txid}", txid()).with(jwt).contentType(MediaType.APPLICATION_JSON).content(body("2.00")))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/pix/v2/cob").with(jwt).param("inicio", "2020-01-01T00:00:00Z").param("fim", "2999-01-01T00:00:00Z")
                        .param("paginacao.itensPorPagina", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parametros.paginacao.itensPorPagina").value(2))
                .andExpect(jsonPath("$.parametros.paginacao.quantidadeTotalDeItens").value(3))
                .andExpect(jsonPath("$.parametros.paginacao.quantidadeDePaginas").value(2))
                .andExpect(jsonPath("$.cobs.length()").value(2));
        // outra conta enxerga zero
        mvc.perform(get("/api/pix/v2/cob").with(TestJwt.client("outro", List.of(UUID.randomUUID()), "cob.read"))
                        .param("inicio", "2020-01-01T00:00:00Z").param("fim", "2999-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.parametros.paginacao.quantidadeTotalDeItens").value(0));
        // inicio > fim → 400
        mvc.perform(get("/api/pix/v2/cob").with(jwt).param("inicio", "2999-01-01T00:00:00Z").param("fim", "2020-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Escopo pii.document.read libera o CPF completo do devedor (B8)")
    void fullDocumentOnlyWithPiiScope() throws Exception {
        String txid = txid();
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(TestJwt.client("cli", List.of(account.id()), "cob.write"))
                .contentType(MediaType.APPLICATION_JSON).content(body("3.00"))).andExpect(status().isCreated());
        mvc.perform(get("/api/pix/v2/cob/{txid}", txid).with(TestJwt.client("cli", List.of(account.id()), "cob.read", "pii.document.read")))
                .andExpect(jsonPath("$.devedor.cpf").value(DEBTOR_CPF));
    }
}
