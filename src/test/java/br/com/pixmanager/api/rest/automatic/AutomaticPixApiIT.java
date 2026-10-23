package br.com.pixmanager.api.rest.automatic;

import br.com.pixmanager.core.port.in.automatic.ExecuteAutomaticUseCase;
import br.com.pixmanager.core.port.in.ledger.ProcessReservationUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase.SpiStatus;
import br.com.pixmanager.core.port.out.AutomaticGatewayPortOut;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut.GatewayResult;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.TestDocuments;
import br.com.pixmanager.support.integration.AbstractApplicationIT;
import br.com.pixmanager.support.integration.AccountFixtures.Account;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Pix Automático (pagador): autorização, cobrança, execução na data, nova tentativa com allow_retry, expiração e cancelamento")
@Import(AutomaticPixApiIT.DaytimeClock.class)
class AutomaticPixApiIT extends AbstractApplicationIT {

    @TestConfiguration
    static class DaytimeClock {
        @Bean @Primary FixedClock testClock() {
            return FixedClock.at(TODAY);
        }
    }

    static final String TODAY = "2026-10-15T15:00:00Z";          // 12:00 em Brasília
    static final String DUE = "2026-10-18";
    static final String DUE_NOON = "2026-10-18T15:00:00Z";

    @Autowired FixedClock clock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ExecuteAutomaticUseCase worker;
    @Autowired ProcessReservationUseCase processReservation;
    @Autowired ApplyPaymentStatusUseCase applyStatus;
    @MockitoBean LedgerGatewayPortOut ledger;
    @MockitoBean AutomaticGatewayPortOut gateway;

    Account payer;
    String payerCpf;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse(TODAY));
        reset(ledger, gateway);
        when(ledger.reserve(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(ledger.confirm(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(ledger.cancel(any())).thenReturn(new GatewayResult.Done("L-1"));
        payerCpf = TestDocuments.randomCpf();
        payer = fixtures.account(payerCpf, "Pagador");
        jdbc.sql("UPDATE pix_automatic_attempt SET status = 'CANCELADA' WHERE status IN ('AGENDADA', 'SOLICITADA')").update();      // isola o worker de outros testes
        jdbc.sql("UPDATE pix_automatic_authorization SET request_expires_at = now() + interval '30 days' WHERE status = 'CRIADA'").update();
    }

    /** O claim ignora tentativas tocadas há menos de 60 s (updated_at é o now() do banco); com o relógio fixo no passado, isso dependeria da data real de execução. */
    ExecuteAutomaticUseCase.Result cycle() {
        jdbc.sql("UPDATE pix_automatic_attempt SET updated_at = '2000-01-01' WHERE status = 'AGENDADA'").update();
        return worker.runCycle();
    }

    org.springframework.test.web.servlet.request.RequestPostProcessor jwt(String... scopes) {
        return TestJwt.client("psp", List.of(payer.id()), scopes);
    }

    static String idRec(String retryFlag) {
        return "R" + retryFlag + UUID.randomUUID().toString().replace("-", "").substring(0, 27);
    }

    JsonNode receive(String idRec, String extra) throws Exception {
        var mvcResult = mvc.perform(post("/v1/automatic-authorizations").with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"idRec":"%s","payerAccountId":"%s","payerDocument":"%s","receiverDocument":"%s","receiverName":"Academia Forma","contract":"CONTRATO-77",
                         "objectDescription":"mensalidade","periodicity":"MENSAL","startDate":"2026-10-01"%s}""".formatted(idRec, payer.id(), payerCpf, TestDocuments.randomCnpj(), extra))).andReturn();
        assertThat(mvcResult.getResponse().getStatus()).as(mvcResult.getResponse().getContentAsString()).isEqualTo(201);
        return json.readTree(mvcResult.getResponse().getContentAsString());
    }

    JsonNode approved(String idRec, String extra) throws Exception {
        String id = receive(idRec, extra).get("id").asText();
        var mvcResult = mvc.perform(post("/v1/automatic-authorizations/{id}/approval", id).with(jwt("automatic.write"))).andReturn();
        assertThat(mvcResult.getResponse().getStatus()).as(mvcResult.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(mvcResult.getResponse().getContentAsString());
    }

    org.springframework.test.web.servlet.ResultActions charge(String authId, String txid, String due, String amount) throws Exception {
        return mvc.perform(post("/v1/automatic-authorizations/{id}/charges", authId).with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"txid\":\"%s\",\"dueDate\":\"%s\",\"amount\":\"%s\",\"receiverIspb\":\"12345678\",\"receiverBranch\":\"0001\",\"receiverAccount\":\"998877\",\"receiverAccountType\":\"CACC\"}"
                        .formatted(txid, due, amount)));
    }

    static String txid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    String chargeStatus(String chargeId) {
        return jdbc.sql("SELECT status FROM pix_automatic_charge WHERE id = :id").param("id", UUID.fromString(chargeId)).query(String.class).single();
    }

    List<String> attemptStatuses(String chargeId) {
        return jdbc.sql("SELECT attempt_type || ':' || status FROM pix_automatic_attempt WHERE charge_id = :c ORDER BY created_at, settlement_date").param("c", UUID.fromString(chargeId)).query(String.class).list();
    }

    UUID reservationOfOrder(String chargeId) {
        return jdbc.sql("SELECT r.id FROM pix_automatic_attempt t JOIN payment_order o ON o.id = t.payment_order_id JOIN ledger_reservation r ON r.idempotency_key = o.end_to_end_id WHERE t.charge_id = :c AND t.status = 'SOLICITADA'")
                .param("c", UUID.fromString(chargeId)).query(UUID.class).single();
    }

    String e2eOfOpenAttempt(String chargeId) {
        return jdbc.sql("SELECT end_to_end_id FROM pix_automatic_attempt WHERE charge_id = :c AND status = 'SOLICITADA'").param("c", UUID.fromString(chargeId)).query(String.class).single();
    }

    @Test
    @DisplayName("Autorização: recebe (idRec valida, allow_retry derivado), recusa documento ≠ titular e idRec repetido; aprova com teto e avisa o recebedor; rejeita; cancela; ajusta o limite")
    void shouldManageAuthorizations() throws Exception {
        String recurrenceId = idRec("R");
        JsonNode authorization = receive(recurrenceId, ",\"fixedAmount\":\"50.00\"");
        assertThat(authorization.get("allowRetry").asBoolean()).isTrue();
        assertThat(authorization.get("status").asText()).isEqualTo("CRIADA");
        assertThat(authorization.get("receiverDocumentMasked").asText()).contains("*");
        assertThat(receive(idRec("N"), "").get("allowRetry").asBoolean()).isFalse();

        mvc.perform(post("/v1/automatic-authorizations").with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idRec\":\"%s\",\"payerAccountId\":\"%s\",\"payerDocument\":\"%s\",\"receiverDocument\":\"%s\",\"receiverName\":\"X\",\"contract\":\"C\",\"periodicity\":\"MENSAL\",\"startDate\":\"2026-10-01\"}"
                        .formatted(idRec("R"), payer.id(), TestDocuments.randomCpf(), TestDocuments.randomCnpj()))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/v1/automatic-authorizations").with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idRec\":\"%s\",\"payerAccountId\":\"%s\",\"payerDocument\":\"%s\",\"receiverDocument\":\"%s\",\"receiverName\":\"X\",\"contract\":\"C\",\"periodicity\":\"MENSAL\",\"startDate\":\"2026-10-01\"}"
                        .formatted(recurrenceId, payer.id(), payerCpf, TestDocuments.randomCnpj()))).andExpect(status().isConflict());
        mvc.perform(post("/v1/automatic-authorizations/{id}/approval", authorization.get("id").asText()).with(jwt("automatic.read"))).andExpect(status().isForbidden());

        String id = authorization.get("id").asText();
        mvc.perform(post("/v1/automatic-authorizations/{id}/approval", id).with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON).content("{\"payerMaxAmount\":\"10.00\"}"))
                .andExpect(status().isUnprocessableEntity());                                           // teto abaixo do valor fixo
        mvc.perform(post("/v1/automatic-authorizations/{id}/approval", id).with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON).content("{\"payerMaxAmount\":\"80.00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APROVADA")).andExpect(jsonPath("$.requestStatus").value("ACEITA")).andExpect(jsonPath("$.payerMaxAmount").value("80.00"));
        verify(gateway).approve(eq(recurrenceId), any());
        mvc.perform(post("/v1/automatic-authorizations/{id}/approval", id).with(jwt("automatic.write"))).andExpect(status().isUnprocessableEntity());   // já decidida
        mvc.perform(put("/v1/automatic-authorizations/{id}/limit", id).with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON).content("{\"payerMaxAmount\":\"60.00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.payerMaxAmount").value("60.00"));

        String other = receive(idRec("R"), "").get("id").asText();
        mvc.perform(post("/v1/automatic-authorizations/{id}/rejection", other).with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON).content("{\"rejectionCode\":\"AP99\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/automatic-authorizations/{id}/rejection", other).with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON).content("{\"rejectionCode\":\"AP13\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJEITADA")).andExpect(jsonPath("$.rejectionCode").value("AP13"));

        mvc.perform(post("/v1/automatic-authorizations/{id}/cancellation", id).with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON).content("{\"cancelledBy\":\"PAYER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELADA")).andExpect(jsonPath("$.cancelledBy").value("PAYER"));
        verify(gateway).cancel(recurrenceId, "PAYER");
        mvc.perform(get("/v1/automatic-authorizations/{id}", id).with(TestJwt.client("outro", List.of(UUID.randomUUID()), "automatic.read"))).andExpect(status().isNotFound());
        assertThat(fixtures.outboxCount("AutomaticAuthorizationApproved")).isPositive();
    }

    @Test
    @DisplayName("Cobrança: sem aprovação ⇒ PIX-422-060; antecedência, vigência, valor fixo e teto são validados; txid repetido ⇒ 409; cancela até a véspera")
    void shouldValidateCharges() throws Exception {
        String pending = receive(idRec("R"), "").get("id").asText();
        charge(pending, txid(), DUE, "50.00").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-060"));

        String id = approved(idRec("R"), ",\"fixedAmount\":\"50.00\"").get("id").asText();
        charge(id, txid(), "2026-10-16", "50.00").andExpect(status().isUnprocessableEntity());             // 1 dia: antes do mínimo de 2
        charge(id, txid(), "2026-11-20", "50.00").andExpect(status().isUnprocessableEntity());             // além de 10 dias
        charge(id, txid(), DUE, "51.00").andExpect(status().isUnprocessableEntity());                      // difere do valor fixo
        charge(id, "curto", DUE, "50.00").andExpect(status().isBadRequest());
        String chargeTxid = txid();
        var created = charge(id, chargeTxid, DUE, "50.00").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ATIVA")).andReturn();
        charge(id, chargeTxid, DUE, "50.00").andExpect(status().isConflict());
        String chargeId = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        assertThat(attemptStatuses(chargeId)).containsExactly("AGND:AGENDADA");

        mvc.perform(get("/v1/automatic-authorizations/{id}/charges/{c}/attempts", id, chargeId).with(jwt("automatic.read"))).andExpect(status().isOk()).andExpect(jsonPath("$[0].type").value("AGND"));
        mvc.perform(post("/v1/automatic-authorizations/{id}/charges/{c}/cancellation", id, chargeId).with(jwt("automatic.write"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELADA"));
        assertThat(attemptStatuses(chargeId)).containsExactly("AGND:CANCELADA");
        mvc.perform(post("/v1/automatic-authorizations/{id}/charges/{c}/cancellation", id, chargeId).with(jwt("automatic.write"))).andExpect(status().isUnprocessableEntity());

        String late = json.readTree(charge(id, txid(), DUE, "50.00").andReturn().getResponse().getContentAsString()).get("id").asText();
        clock.set(Instant.parse("2026-10-18T03:30:00Z"));                                                  // 00:30 do dia 18 em Brasília: passou da véspera
        mvc.perform(post("/v1/automatic-authorizations/{id}/charges/{c}/cancellation", id, late).with(jwt("automatic.write"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Execução: na data cria a ordem AUTOMATIC (e2eId da ordem vira o da tentativa); SPI enviou ⇒ tentativa PAGA e cobrança CONCLUIDA; ciclo repetido não duplica")
    void shouldPayOnTheDueDate() throws Exception {
        String id = approved(idRec("R"), "").get("id").asText();
        String chargeId = json.readTree(charge(id, txid(), DUE, "50.00").andReturn().getResponse().getContentAsString()).get("id").asText();

        assertThat(cycle().started()).as("ainda é 15/10").isZero();
        clock.set(Instant.parse(DUE_NOON));
        assertThat(cycle().started()).isEqualTo(1);
        assertThat(cycle().started()).isZero();
        assertThat(attemptStatuses(chargeId)).containsExactly("AGND:SOLICITADA");
        String endToEndId = e2eOfOpenAttempt(chargeId);
        assertThat(jdbc.sql("SELECT origin FROM payment_order WHERE end_to_end_id = :e").param("e", endToEndId).query(String.class).single()).isEqualTo("AUTOMATIC");

        processReservation.process(reservationOfOrder(chargeId));
        applyStatus.apply("it", "a1", "pix.spi.payment-status.v1", 0, 1, endToEndId, SpiStatus.SENT, null, Instant.now());
        assertThat(cycle().paid()).isEqualTo(1);

        assertThat(chargeStatus(chargeId)).isEqualTo("CONCLUIDA");
        assertThat(attemptStatuses(chargeId)).containsExactly("AGND:PAGA");
    }

    @Test
    @DisplayName("Saldo insuficiente: com allow_retry (RR…) agenda NTAG no dia seguinte; sem (RN…) a cobrança é REJEITADA; autorização cancelada ou teto reduzido bloqueiam o débito")
    void shouldRetryOnlyWhenAllowed() throws Exception {
        when(ledger.reserve(any())).thenReturn(new GatewayResult.Refused(GatewayResult.Reason.INSUFFICIENT_FUNDS, "sem saldo"));
        String retryAuth = approved(idRec("R"), "").get("id").asText();
        String noRetryAuth = approved(idRec("N"), "").get("id").asText();
        String withRetry = json.readTree(charge(retryAuth, txid(), DUE, "50.00").andReturn().getResponse().getContentAsString()).get("id").asText();
        String noRetry = json.readTree(charge(noRetryAuth, txid(), DUE, "50.00").andReturn().getResponse().getContentAsString()).get("id").asText();

        clock.set(Instant.parse(DUE_NOON));
        assertThat(cycle().started()).isEqualTo(2);
        processReservation.process(reservationOfOrder(withRetry));
        processReservation.process(reservationOfOrder(noRetry));
        var cycleResult = cycle();

        assertThat(cycleResult.retried()).isEqualTo(1);
        assertThat(attemptStatuses(withRetry)).containsExactly("AGND:REJEITADA", "NTAG:AGENDADA");
        assertThat(chargeStatus(withRetry)).isEqualTo("ATIVA");
        assertThat(chargeStatus(noRetry)).isEqualTo("REJEITADA");

        clock.set(Instant.parse("2026-10-19T15:00:00Z"));                                                  // dia seguinte: a NTAG dispara
        assertThat(cycle().started()).isEqualTo(1);
        assertThat(attemptStatuses(withRetry)).containsExactly("AGND:REJEITADA", "NTAG:SOLICITADA");

        when(ledger.reserve(any())).thenReturn(new GatewayResult.Done("L-2"));                            // cancelar a autorização barra o débito de uma cobrança AGENDADA
        String authorization = approved(idRec("R"), "").get("id").asText();
        clock.set(Instant.parse(TODAY));
        String cancelled = json.readTree(charge(authorization, txid(), DUE, "50.00").andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/v1/automatic-authorizations/{id}/cancellation", authorization).with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON).content("{\"cancelledBy\":\"RECEIVER\"}")).andExpect(status().isOk());
        assertThat(chargeStatus(cancelled)).isEqualTo("CANCELADA");
        assertThat(attemptStatuses(cancelled)).containsExactly("AGND:CANCELADA");

        String lowered = approved(idRec("R"), "").get("id").asText();
        String loweredCharge = json.readTree(charge(lowered, txid(), DUE, "50.00").andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(put("/v1/automatic-authorizations/{id}/limit", lowered).with(jwt("automatic.write")).contentType(MediaType.APPLICATION_JSON).content("{\"payerMaxAmount\":\"20.00\"}")).andExpect(status().isOk());
        clock.set(Instant.parse(DUE_NOON));
        cycle();
        assertThat(chargeStatus(loweredCharge)).as("teto reduzido depois da cobrança").isEqualTo("REJEITADA");
    }

    @Test
    @DisplayName("Solicitação não decidida no prazo expira (EXPIRADA) e o worker registra")
    void shouldExpireUndecidedRequests() throws Exception {
        String id = receive(idRec("R"), "").get("id").asText();
        clock.set(Instant.parse("2026-10-30T15:00:00Z"));

        assertThat(cycle().expired()).isPositive();

        mvc.perform(get("/v1/automatic-authorizations/{id}", id).with(jwt("automatic.read"))).andExpect(jsonPath("$.status").value("EXPIRADA"));
        mvc.perform(post("/v1/automatic-authorizations/{id}/approval", id).with(jwt("automatic.write"))).andExpect(status().isUnprocessableEntity());
    }
}
