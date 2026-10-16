package br.com.pixmanager.api.rest.med;

import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.ValueComponent;
import br.com.pixmanager.core.domain.enums.ComponentType;
import br.com.pixmanager.core.port.in.ledger.ProcessReservationUseCase;
import br.com.pixmanager.core.port.in.med.FollowMedCasesUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase.SpiStatus;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase.ReceivePixCommand;
import br.com.pixmanager.core.port.in.refund.SettleRefundUseCase;
import br.com.pixmanager.core.port.out.MedGatewayPortOut;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut.GatewayResult;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("MED: abertura (REPORTED/REPORTER), aceite com bloqueio, devolução MED, encerramento, contestação, prazos e acesso")
class MedCaseApiIT extends AbstractApplicationIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ReceivePixUseCase receivePix;
    @Autowired ProcessReservationUseCase processReservation;
    @Autowired SettleRefundUseCase settleRefund;
    @Autowired FollowMedCasesUseCase follow;
    @Autowired ApplyPaymentStatusUseCase applyStatus;
    @MockitoBean LedgerGatewayPortOut ledger;
    @MockitoBean MedGatewayPortOut gateway;

    Account account;

    @BeforeEach
    void setUp() {
        account = fixtures.account(TestDocuments.randomCpf(), "Cliente");
        when(ledger.reserve(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(ledger.confirm(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(ledger.cancel(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(gateway.report(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn("FR-123", "FR-124");
    }

    org.springframework.test.web.servlet.request.RequestPostProcessor admin(String... scopes) {
        return TestJwt.client("backoffice", List.of(account.id()), scopes);
    }

    String e2e() {
        return "E12345678202610151403" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    String receive(String amount, Instant settledAt) {
        String e = e2e();
        receivePix.receive(new ReceivePixCommand("it-group", UUID.randomUUID().toString(), "pix.spi.payment-settled.v1", 0, 1, e, null, Money.of(amount), settledAt,
                "12345678", "0001", account.number(), "CACC", "11144477735", "Maria Pagadora", "87654321", "0002", "99887", "CACC", "x",
                List.of(new ValueComponent(ComponentType.ORIGINAL, Money.of(amount), null, null))));
        return e;
    }

    String openBody(String role, String e2e) {
        return "{\"role\":\"%s\",\"endToEndId\":\"%s\",\"caseType\":\"FRAUD\",\"description\":\"golpe do falso suporte\",\"layer\":1}".formatted(role, e2e);
    }

    JsonNode open(String role, String e2e) throws Exception {
        var r = mvc.perform(post("/v1/med-cases").with(admin("med.write")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(openBody(role, e2e))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return json.readTree(r.getResponse().getContentAsString());
    }

    JsonNode act(String id, String verb) throws Exception {
        var r = mvc.perform(post("/v1/med-cases/{id}/{verb}", id, verb).with(admin("med.write"))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    UUID reservationOf(String key) {
        return jdbc.sql("SELECT id FROM ledger_reservation WHERE idempotency_key = :k").param("k", key).query(UUID.class).single();
    }

    @Test
    @DisplayName("REPORTED: aceita ⇒ bloqueio no core ⇒ devolução MED_FRAUDE ⇒ liquidada ⇒ CLOSED ⇒ contestação aceita ⇒ CONTESTED; histórico e eventos completos")
    void shouldRunTheWholeReportedFlow() throws Exception {
        String e = receive("100.00", Instant.now());
        int opened = fixtures.outboxCount("MedCaseOpened");

        JsonNode c = open("REPORTED", e);
        String id = c.get("id").asText();
        assertThat(c.get("status").asText()).isEqualTo("OPEN");
        assertThat(c.get("trackedAmount").asText()).isEqualTo("100.00");
        assertThat(fixtures.outboxCount("MedCaseOpened")).isEqualTo(opened + 1);
        mvc.perform(post("/v1/med-cases").with(admin("med.write")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(openBody("REPORTED", e))).andExpect(status().isConflict());

        assertThat(act(id, "acceptance").get("status").asText()).isEqualTo("ACCEPTED");
        processReservation.process(reservationOf("med-" + id + "-1"));
        mvc.perform(get("/v1/med-cases/{id}", id).with(admin("med.read"))).andExpect(jsonPath("$.blockedAmount").value("100.00"));
        assertThat(jdbc.sql("SELECT count(*) FROM med_block WHERE case_id = :c AND status = 'ACTIVE'").param("c", UUID.fromString(id)).query(Integer.class).single()).isEqualTo(1);

        JsonNode refunding = act(id, "refund");
        assertThat(refunding.get("status").asText()).isEqualTo("REFUNDING");
        verify(ledger).cancel(org.mockito.ArgumentMatchers.argThat(r -> r.idempotencyKey().equals("med-" + id + "-1")));
        String rtr = jdbc.sql("SELECT rtr_id FROM pix_refund WHERE id = :id").param("id", UUID.fromString(refunding.get("refundId").asText())).query(String.class).single();
        assertThat(jdbc.sql("SELECT nature FROM pix_refund WHERE rtr_id = :r").param("r", rtr).query(String.class).single()).isEqualTo("MED_FRAUDE");
        assertThat(jdbc.sql("SELECT status FROM med_block WHERE case_id = :c").param("c", UUID.fromString(id)).query(String.class).single()).isEqualTo("RELEASED");

        assertThat(follow.runCycle()).as("devolução ainda em processamento").isZero();
        processReservation.process(reservationOf(rtr));
        settleRefund.apply("it-med", "s-" + id, "pix.spi.refund-status.v1", 0, 1, rtr, true, null, Instant.now());
        assertThat(follow.runCycle()).isEqualTo(1);
        mvc.perform(get("/v1/med-cases/{id}", id).with(admin("med.read"))).andExpect(jsonPath("$.status").value("CLOSED"));

        var contest = mvc.perform(post("/v1/med-cases/{id}/contestations", id).with(admin("med.write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"o valor era legítimo\"}")).andExpect(status().isCreated()).andReturn();
        String cid = json.readTree(contest.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/v1/med-cases/{id}/contestations", id).with(admin("med.write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"de novo\"}")).andExpect(status().isConflict());
        mvc.perform(post("/v1/med-cases/contestations/{c}/decision", cid).with(admin("med.write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accepted\":true,\"note\":\"procede\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(get("/v1/med-cases/{id}", id).with(admin("med.read"))).andExpect(jsonPath("$.status").value("CONTESTED"));

        var history = mvc.perform(get("/v1/med-cases/{id}/history", id).with(admin("med.read"))).andExpect(status().isOk()).andReturn();
        JsonNode h = json.readTree(history.getResponse().getContentAsString());
        assertThat(h).hasSizeGreaterThanOrEqualTo(7);
        assertThat(h.get(0).get("to").asText()).isEqualTo("OPEN");
        assertThat(h.get(h.size() - 1).get("to").asText()).isEqualTo("CONTESTED");
        assertThat(fixtures.outboxCount("MedCaseRefundSettled")).isPositive();
    }

    @Test
    @DisplayName("Prazos e regras: fora dos 80 dias ⇒ 422 PIX-422-071; recusa; cancelamento libera o bloqueio; papel errado e escopo ⇒ 422/403; outro cliente ⇒ 404")
    void shouldEnforceWindowsRolesAndAccess() throws Exception {
        String old = receive("50.00", Instant.now().minus(81, ChronoUnit.DAYS));
        mvc.perform(post("/v1/med-cases").with(admin("med.write")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(openBody("REPORTED", old)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-071"));
        mvc.perform(post("/v1/med-cases").with(admin("med.write")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(openBody("REPORTED", e2e()))).andExpect(status().isNotFound());

        String refused = open("REPORTED", receive("30.00", Instant.now())).get("id").asText();
        assertThat(act(refused, "refusal").get("status").asText()).isEqualTo("REFUSED");
        mvc.perform(post("/v1/med-cases/{id}/acceptance", refused).with(admin("med.write"))).andExpect(status().isUnprocessableEntity());

        String cancelled = open("REPORTED", receive("40.00", Instant.now())).get("id").asText();
        act(cancelled, "acceptance");
        processReservation.process(reservationOf("med-" + cancelled + "-1"));
        JsonNode result = act(cancelled, "cancellation");
        assertThat(result.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(result.get("blockedAmount").asText()).isEqualTo("0.00");
        verify(ledger).cancel(org.mockito.ArgumentMatchers.argThat(r -> r.idempotencyKey().equals("med-" + cancelled + "-1")));

        String wrongRole = open("REPORTED", receive("20.00", Instant.now())).get("id").asText();
        mvc.perform(post("/v1/med-cases/{id}/closure", wrongRole).with(admin("med.write"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/v1/med-cases/{id}/refund", wrongRole).with(admin("med.write"))).andExpect(status().isUnprocessableEntity());   // OPEN, sem bloqueio
        mvc.perform(post("/v1/med-cases/{id}/acceptance", wrongRole).with(admin("med.read"))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/med-cases/{id}", wrongRole).with(TestJwt.client("outro", List.of(UUID.randomUUID()), "med.read"))).andExpect(status().isNotFound());
        mvc.perform(get("/v1/med-cases").param("status", "OPEN").with(admin("med.read"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").isNumber());
    }

    @Test
    @DisplayName("Core recusa o bloqueio (sem saldo): o caso fica ACCEPTED sem valor bloqueado, o motivo vai ao histórico e a devolução é negada")
    void shouldKeepTheCaseWhenTheBlockIsRefused() throws Exception {
        String id = open("REPORTED", receive("100.00", Instant.now())).get("id").asText();
        when(ledger.reserve(any())).thenReturn(new GatewayResult.Refused(GatewayResult.Reason.INSUFFICIENT_FUNDS, "sem saldo"));
        act(id, "acceptance");

        processReservation.process(reservationOf("med-" + id + "-1"));

        mvc.perform(get("/v1/med-cases/{id}", id).with(admin("med.read"))).andExpect(jsonPath("$.status").value("ACCEPTED")).andExpect(jsonPath("$.blockedAmount").value("0.00"));
        assertThat(jdbc.sql("SELECT note FROM med_case_history WHERE case_id = :c ORDER BY occurred_at DESC LIMIT 1").param("c", UUID.fromString(id)).query(String.class).single())
                .contains("Bloqueio recusado");
        mvc.perform(post("/v1/med-cases/{id}/refund", id).with(admin("med.write"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("REPORTER: só pagamento LIQUIDADO; envia ao DICT (id guardado); cancelar avisa o DICT; encerrar registra o resultado")
    void shouldReportAsThePayer() throws Exception {
        DictGatewayStub.reset();
        DictGatewayStub.lookupFound(TestDocuments.randomCpf());
        fixtures.trustedDevice(account.id(), "dev-1");
        var created = mvc.perform(post("/v1/payment-orders").with(TestJwt.client("cli", List.of(account.id()), "payment.write")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"payerAccountId\":\"%s\",\"method\":\"KEY\",\"amount\":\"10.00\",\"deviceId\":\"dev-1\",\"destination\":{\"pixKeyType\":\"EMAIL\",\"pixKey\":\"alguem@exemplo.com.br\"}}".formatted(account.id())))
                .andExpect(status().isCreated()).andReturn();
        JsonNode order = json.readTree(created.getResponse().getContentAsString());
        String e = order.get("endToEndId").asText();

        mvc.perform(post("/v1/med-cases").with(admin("med.write")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(openBody("REPORTER", e))).andExpect(status().isUnprocessableEntity());   // ainda não liquidado

        processReservation.process(reservationOf(e));
        applyStatus.apply("it", "m1", "pix.spi.payment-status.v1", 0, 1, e, SpiStatus.SENT, null, Instant.now());
        applyStatus.apply("it", "m2", "pix.spi.payment-status.v1", 0, 2, e, SpiStatus.SETTLED, null, Instant.now());

        JsonNode c = open("REPORTER", e);
        assertThat(c.get("dictFundsRecoveryId").asText()).isEqualTo("FR-123");
        String id = c.get("id").asText();
        mvc.perform(post("/v1/med-cases/{id}/acceptance", id).with(admin("med.write"))).andExpect(status().isUnprocessableEntity());   // aceite é do REPORTED
        assertThat(act(id, "closure").get("status").asText()).isEqualTo("CLOSED");

        String second = e;
        JsonNode again = open("REPORTER", second);       // o anterior terminou: pode reabrir (outro id do DICT)
        assertThat(act(again.get("id").asText(), "cancellation").get("status").asText()).isEqualTo("CANCELLED");
        verify(gateway).cancel("FR-124");
    }
}
