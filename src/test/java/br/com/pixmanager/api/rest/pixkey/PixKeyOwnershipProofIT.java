package br.com.pixmanager.api.rest.pixkey;

import br.com.pixmanager.core.domain.enums.OtpChannel;
import br.com.pixmanager.core.port.in.pixkey.ActivatePendingKeysUseCase;
import br.com.pixmanager.core.port.out.OtpSenderPortOut;
import br.com.pixmanager.support.FixedClock;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Prova de posse (OTP) de chave e-mail/telefone: emitir, verificar, limites e ativação no DICT")
@Import(PixKeyOwnershipProofIT.TestBeans.class)
class PixKeyOwnershipProofIT extends AbstractApplicationIT {

    /** Relógio controlável e canal de envio que só ANOTA (o código nunca passa por log nem pela resposta). */
    @TestConfiguration
    static class TestBeans {
        static final RecordingSender SENDER = new RecordingSender();

        @Bean @Primary FixedClock otpTestClock() {
            return FixedClock.at("2026-10-16T15:00:00Z");
        }

        @Bean @Primary OtpSenderPortOut otpTestSender() {
            return SENDER;
        }
    }

    record Sent(OtpChannel channel, String destination, String code) {}

    static class RecordingSender implements OtpSenderPortOut {
        final List<Sent> sent = new ArrayList<>();

        @Override
        public void send(OtpChannel channel, String destination, String code, Duration validFor) {
            sent.add(new Sent(channel, destination, code));
        }

        Sent last() {
            return sent.get(sent.size() - 1);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ActivatePendingKeysUseCase activate;
    @Autowired FixedClock clock;

    Account account;

    @BeforeEach
    void setUp() {
        DictGatewayStub.reset();
        TestBeans.SENDER.sent.clear();
        clock.set(Instant.parse("2026-10-16T15:00:00Z"));
        account = fixtures.account(TestDocuments.randomCpf(), "Titular PF");
    }

    org.springframework.test.web.servlet.request.RequestPostProcessor jwt(String... scopes) {
        return TestJwt.client("cli", List.of(account.id()), scopes);
    }

    String newEmailKey() throws Exception {
        String email = "otp-" + UUID.randomUUID() + "@exemplo.com.br";
        MvcResult mvcResult = mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"pixKeyType\":\"EMAIL\",\"pixKey\":\"%s\"}".formatted(account.id(), email)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_OWNERSHIP_PROOF")).andReturn();
        return json.readTree(mvcResult.getResponse().getContentAsString()).get("id").asText();
    }

    JsonNode challenge(String keyId) throws Exception {
        MvcResult mvcResult = mvc.perform(post("/v1/pix-keys/" + keyId + "/ownership-proof/challenges").with(jwt("pixkey.write")))
                .andExpect(status().isAccepted()).andReturn();
        return json.readTree(mvcResult.getResponse().getContentAsString());
    }

    org.springframework.test.web.servlet.ResultActions verify(String keyId, String code) throws Exception {
        return mvc.perform(post("/v1/pix-keys/" + keyId + "/ownership-proof/verifications").with(jwt("pixkey.write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }

    String statusOf(String keyId) {
        return jdbc.sql("SELECT status FROM pix_key WHERE id = :id").param("id", UUID.fromString(keyId)).query(String.class).single();
    }

    @Test
    @DisplayName("Fluxo completo: 202 (código só no canal) → código certo ⇒ PENDING_DICT → job registra no DICT ⇒ ACTIVE; o código não vai à resposta nem ao banco")
    void shouldProveOwnershipAndActivateTheKey() throws Exception {
        String keyId = newEmailKey();

        MvcResult issued = mvc.perform(post("/v1/pix-keys/" + keyId + "/ownership-proof/challenges").with(jwt("pixkey.write")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.challengeId").exists()).andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.destination").value(containsString("***")))          // máscara: esconde a parte local do e-mail
                .andExpect(jsonPath("$.destination").value(not(containsString("otp-"))))
                .andReturn();
        String code = TestBeans.SENDER.last().code();
        assertThat(code).matches("\\d{6}");
        assertThat(issued.getResponse().getContentAsString()).doesNotContain(code);
        String stored = jdbc.sql("SELECT code_hash FROM otp_challenge WHERE pix_key_id = :k").param("k", UUID.fromString(keyId)).query(String.class).single();
        assertThat(stored).hasSize(64).isNotEqualTo(code).doesNotContain(code);
        assertThat(statusOf(keyId)).isEqualTo("PENDING_OWNERSHIP_PROOF");

        verify(keyId, code).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_DICT"));
        DictGatewayStub.registerOk("cid-otp");
        clock.advance(Duration.ofSeconds(2));                                              // a ativação exige a chave PENDING_DICT há >= 1 s (activationMinAge)
        activate.activatePending(50);
        assertThat(statusOf(keyId)).isEqualTo("ACTIVE");
        verify(keyId, code).andExpect(status().isUnprocessableEntity());                  // o mesmo código não vale duas vezes
    }

    @Test
    @DisplayName("Código errado ⇒ 422 PIX-422-014 com as tentativas restantes; esgotadas ⇒ PIX-422-016 até pedir outro (e o antigo morre)")
    void shouldBurnAttemptsAndRequireANewCode() throws Exception {
        String keyId = newEmailKey();
        challenge(keyId);
        String code = TestBeans.SENDER.last().code();
        String wrong = code.equals("000000") ? "000001" : "000000";

        for (int left = 4; left >= 0; left--) {
            verify(keyId, wrong).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-014"));
        }
        verify(keyId, code).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-016"));
        assertThat(statusOf(keyId)).isEqualTo("PENDING_OWNERSHIP_PROOF");

        clock.advance(Duration.ofSeconds(61));
        challenge(keyId);
        String fresh = TestBeans.SENDER.last().code();
        verify(keyId, fresh).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Espera entre pedidos: 429 PIX-429-002 com Retry-After em segundos; vencido o prazo, volta a emitir; código vencido ⇒ 422 PIX-422-015")
    void shouldRateLimitAndExpire() throws Exception {
        String keyId = newEmailKey();
        challenge(keyId);
        String first = TestBeans.SENDER.last().code();

        clock.advance(Duration.ofSeconds(20));
        mvc.perform(post("/v1/pix-keys/" + keyId + "/ownership-proof/challenges").with(jwt("pixkey.write")))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("PIX-429-002"))
                .andExpect(header().string("Retry-After", "40"));
        assertThat(TestBeans.SENDER.sent).hasSize(1);

        clock.advance(Duration.ofMinutes(5));                                              // passa da validade (5 min)
        verify(keyId, first).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-015"));
        challenge(keyId);
        assertThat(TestBeans.SENDER.sent).hasSize(2);
    }

    @Test
    @DisplayName("Fronteiras: código mal formado ⇒ 400; escopo errado ⇒ 403; sem token ⇒ 401; chave de outra conta ⇒ 404; chave CPF (sem prova) ⇒ 422")
    void shouldGuardTheBoundaries() throws Exception {
        String keyId = newEmailKey();
        challenge(keyId);

        verify(keyId, "12ab56").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PIX-400-001"));
        verify(keyId, "12345").andExpect(status().isBadRequest());
        mvc.perform(post("/v1/pix-keys/" + keyId + "/ownership-proof/challenges").with(jwt("pixkey.read"))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/pix-keys/" + keyId + "/ownership-proof/challenges")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/pix-keys/" + keyId + "/ownership-proof/challenges").with(TestJwt.client("outro", List.of(UUID.randomUUID()), "pixkey.write")))
                .andExpect(status().isNotFound());

        MvcResult cpfKey = mvc.perform(post("/v1/pix-keys").with(jwt("pixkey.write")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"%s\",\"pixKeyType\":\"EVP\"}".formatted(account.id()))).andExpect(status().isCreated()).andReturn();
        String evp = json.readTree(cpfKey.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/v1/pix-keys/" + evp + "/ownership-proof/challenges").with(jwt("pixkey.write")))
                .andExpect(status().isUnprocessableEntity()).andExpect(content().string(containsString("não está aguardando")));
    }
}
