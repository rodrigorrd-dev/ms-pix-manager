package br.com.pixmanager.api.rest.schedule;

import br.com.pixmanager.core.port.in.ledger.ProcessReservationUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase.SpiStatus;
import br.com.pixmanager.core.port.in.schedule.ExecuteScheduledPaymentsUseCase;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut.GatewayResult;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Pix Agendado: criação, validação, cancelamento e execução (sucesso, saldo insuficiente 18h–21h, falha de negócio, técnica e atraso)")
@Import(ScheduledPaymentApiIT.DaytimeClock.class)
class ScheduledPaymentApiIT extends AbstractApplicationIT {

    @TestConfiguration
    static class DaytimeClock {
        @Bean @Primary FixedClock testClock() {
            return FixedClock.at(TODAY_NOON);
        }
    }

    static final String TODAY_NOON = "2026-10-15T15:00:00Z";      // 12:00 em Brasília
    static final String DUE_NOON = "2026-10-16T15:00:00Z";        // a data agendada (16/10), meio-dia

    @Autowired FixedClock clock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ExecuteScheduledPaymentsUseCase worker;
    @Autowired ProcessReservationUseCase processReservation;
    @Autowired ApplyPaymentStatusUseCase applyStatus;
    @MockitoBean LedgerGatewayPortOut ledger;

    Account payer;

    @BeforeEach
    void setUp() {
        clock.set(Instant.parse(TODAY_NOON));
        DictGatewayStub.reset();
        DictGatewayStub.lookupFound(TestDocuments.randomCpf());
        reset(ledger);
        when(ledger.reserve(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(ledger.confirm(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(ledger.cancel(any())).thenReturn(new GatewayResult.Done("L-1"));
        payer = fixtures.account(TestDocuments.randomCpf(), "Pagador");
        jdbc.sql("UPDATE scheduled_payment_occurrence SET status = 'CANCELLED' WHERE status IN ('SCHEDULED', 'RETRY_PENDING', 'PROCESSING')").update();   // isola o worker do que outros testes deixaram
    }

    org.springframework.test.web.servlet.request.RequestPostProcessor jwt(String... scopes) {
        return TestJwt.client("cli", List.of(payer.id()), scopes);
    }

    String body(String recurrence, String firstDate, String extra, String amount) {
        return """
                {"payerAccountId":"%s","recurrence":"%s","method":"KEY","amount":"%s","firstDate":"%s","payerMessage":"aluguel"%s,
                 "destination":{"pixKeyType":"EMAIL","pixKey":"alguem@exemplo.com.br"}}""".formatted(payer.id(), recurrence, amount, firstDate, extra);
    }

    JsonNode create(String body) throws Exception {
        var cycleResult = mvc.perform(post("/v1/scheduled-payments").with(jwt("schedule.write")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(cycleResult.getResponse().getStatus()).as(cycleResult.getResponse().getContentAsString()).isEqualTo(201);
        return json.readTree(cycleResult.getResponse().getContentAsString());
    }

    String occurrenceStatus(String scheduleId) {
        return jdbc.sql("SELECT status FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id ORDER BY sequence_no LIMIT 1")
                .param("id", UUID.fromString(scheduleId)).query(String.class).single();
    }

    String scheduleStatus(String id) {
        return jdbc.sql("SELECT status FROM scheduled_payment WHERE id = :id").param("id", UUID.fromString(id)).query(String.class).single();
    }

    UUID orderOf(String scheduleId) {
        return jdbc.sql("SELECT payment_order_id FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id").param("id", UUID.fromString(scheduleId)).query(UUID.class).single();
    }

    String e2eOf(UUID orderId) {
        return jdbc.sql("SELECT end_to_end_id FROM payment_order WHERE id = :id").param("id", orderId).query(String.class).single();
    }

    @Test
    @DisplayName("Criação: 201 com ocorrências nas datas certas (mensal clampa dia 31), replay idempotente, PII mascarada; regras inválidas ⇒ 422 PIX-422-061")
    void shouldCreateAndValidate() throws Exception {
        var key = UUID.randomUUID().toString();
        String monthly = body("MONTHLY", "2026-10-31", ",\"occurrencesTotal\":3", "10.00");
        var first = mvc.perform(post("/v1/scheduled-payments").with(jwt("schedule.write")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(monthly))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.destination.pixKeyMasked").exists()).andReturn();
        String id = json.readTree(first.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/v1/scheduled-payments").with(jwt("schedule.write")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(monthly))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true")).andExpect(jsonPath("$.id").value(id));

        mvc.perform(get("/v1/scheduled-payments/{id}/occurrences", id).with(jwt("schedule.read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].dueDate").value("2026-10-31")).andExpect(jsonPath("$[1].dueDate").value("2026-11-30"))
                .andExpect(jsonPath("$[2].dueDate").value("2026-12-31"));
        mvc.perform(get("/v1/scheduled-payments").param("payerAccountId", payer.id().toString()).with(jwt("schedule.read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));

        for (String bad : List.of(body("ONCE", "2026-10-15", "", "10.00"),                       // hoje não vale: tem que ser futura
                body("ONCE", "2026-10-20", ",\"occurrencesTotal\":3", "10.00"),                  // único não tem quantidade
                body("WEEKLY", "2026-10-20", "", "10.00"),                                       // recorrente sem fim
                body("WEEKLY", "2026-10-20", ",\"occurrencesTotal\":3,\"endDate\":\"2026-12-01\"", "10.00"),   // os dois
                body("ONCE", "2029-01-10", "", "10.00"),                                         // além do horizonte (730 dias)
                body("ONCE", "2026-10-20", "", "0.00"))) {
            mvc.perform(post("/v1/scheduled-payments").with(jwt("schedule.write")).header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-061"));
        }
        mvc.perform(post("/v1/scheduled-payments").with(jwt("schedule.read")).header("Idempotency-Key", "x").contentType(MediaType.APPLICATION_JSON)
                .content(monthly)).andExpect(status().isForbidden());
        mvc.perform(get("/v1/scheduled-payments/{id}", id).with(TestJwt.client("outro", List.of(UUID.randomUUID()), "schedule.read")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Cancelamento: do agendamento (If-Match) cancela as ocorrências em espera; ocorrência avulsa cancela e conclui; fora do estado ⇒ 422 PIX-422-062")
    void shouldCancel() throws Exception {
        JsonNode schedule = create(body("WEEKLY", "2026-10-20", ",\"occurrencesTotal\":2", "10.00"));
        String id = schedule.get("id").asText();
        mvc.perform(post("/v1/scheduled-payments/{id}/cancellation", id).with(jwt("schedule.write"))).andExpect(status().isPreconditionRequired());
        mvc.perform(post("/v1/scheduled-payments/{id}/cancellation", id).with(jwt("schedule.write")).header("If-Match", "\"99\"")).andExpect(status().isPreconditionFailed());
        mvc.perform(post("/v1/scheduled-payments/{id}/cancellation", id).with(jwt("schedule.write")).header("If-Match", "\"0\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.sql("SELECT count(*) FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id AND status = 'CANCELLED'").param("id", UUID.fromString(id)).query(Long.class).single()).isEqualTo(2);
        mvc.perform(post("/v1/scheduled-payments/{id}/cancellation", id).with(jwt("schedule.write")).header("If-Match", "*"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-062"));

        JsonNode two = create(body("WEEKLY", "2026-10-20", ",\"occurrencesTotal\":2", "10.00"));
        List<UUID> occurrenceIds = jdbc.sql("SELECT id FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id ORDER BY sequence_no").param("id", UUID.fromString(two.get("id").asText())).query(UUID.class).list();
        mvc.perform(post("/v1/scheduled-payments/{id}/occurrences/{o}/cancellation", two.get("id").asText(), occurrenceIds.get(0)).with(jwt("schedule.write")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/v1/scheduled-payments/{id}/occurrences/{o}/cancellation", two.get("id").asText(), occurrenceIds.get(0)).with(jwt("schedule.write")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-062"));
        assertThat(scheduleStatus(two.get("id").asText())).isEqualTo("ACTIVE");
        mvc.perform(post("/v1/scheduled-payments/{id}/occurrences/{o}/cancellation", two.get("id").asText(), occurrenceIds.get(1)).with(jwt("schedule.write"))).andExpect(status().isOk());
        assertThat(scheduleStatus(two.get("id").asText())).as("sem ocorrência em aberto ⇒ concluído").isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Execução feliz: antes da data não dispara; na data cria a ordem (origem SCHEDULED, vínculo ocorrência↔ordem); SPI enviou ⇒ EXECUTED e agendamento COMPLETED; ciclo repetido não duplica")
    void shouldExecuteOnTheDate() throws Exception {
        String id = create(body("ONCE", "2026-10-16", "", "50.00")).get("id").asText();
        int executed = fixtures.outboxCount("ScheduledOccurrenceExecuted");

        assertThat(worker.runCycle().started()).as("ainda é 15/10").isZero();

        clock.set(Instant.parse(DUE_NOON));
        assertThat(worker.runCycle().started()).isEqualTo(1);
        assertThat(worker.runCycle().started()).as("segundo ciclo não dispara de novo").isZero();
        assertThat(occurrenceStatus(id)).isEqualTo("PROCESSING");
        UUID order = orderOf(id);
        assertThat(jdbc.sql("SELECT origin FROM payment_order WHERE id = :id").param("id", order).query(String.class).single()).isEqualTo("SCHEDULED");
        assertThat(jdbc.sql("SELECT scheduled_occurrence_id IS NOT NULL FROM payment_order WHERE id = :id").param("id", order).query(Boolean.class).single()).isTrue();

        String endToEndId = e2eOf(order);
        worker.runCycle();
        assertThat(occurrenceStatus(id)).as("ordem ainda VALIDATED").isEqualTo("PROCESSING");
        processReservation.process(jdbc.sql("SELECT id FROM ledger_reservation WHERE idempotency_key = :k").param("k", endToEndId).query(UUID.class).single());
        applyStatus.apply("it", "sch1", "pix.spi.payment-status.v1", 0, 1, endToEndId, SpiStatus.SENT, null, Instant.now());

        assertThat(worker.runCycle().settled()).isEqualTo(1);
        assertThat(occurrenceStatus(id)).isEqualTo("EXECUTED");
        assertThat(scheduleStatus(id)).isEqualTo("COMPLETED");
        assertThat(fixtures.outboxCount("ScheduledOccurrenceExecuted")).isEqualTo(executed + 1);
        assertThat(worker.runCycle().settled()).isZero();
    }

    @Test
    @DisplayName("Saldo insuficiente: retenta no MESMO dia entre 18h e 21h e avisa o cliente; persistindo ⇒ FAILED_INSUFFICIENT_FUNDS (BR-SCH-03)")
    void shouldRetryInTheEveningWindowAndNotify() throws Exception {
        String id = create(body("ONCE", "2026-10-16", "", "50.00")).get("id").asText();
        when(ledger.reserve(any())).thenReturn(new GatewayResult.Refused(GatewayResult.Reason.INSUFFICIENT_FUNDS, "sem saldo"));
        int notices = fixtures.outboxCount("ScheduledOccurrenceRetryNotified");

        clock.set(Instant.parse(DUE_NOON));
        worker.runCycle();
        processReservation.process(jdbc.sql("SELECT id FROM ledger_reservation WHERE idempotency_key = :k").param("k", e2eOf(orderOf(id))).query(UUID.class).single());
        assertThat(worker.runCycle().retried()).isEqualTo(1);

        assertThat(occurrenceStatus(id)).isEqualTo("RETRY_PENDING");
        Instant next = jdbc.sql("SELECT next_attempt_at FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id").param("id", UUID.fromString(id)).query(java.sql.Timestamp.class).single().toInstant();
        assertThat(next).isBetween(Instant.parse("2026-10-16T21:00:00Z"), Instant.parse("2026-10-17T00:00:00Z"));      // 18h–21h em Brasília
        assertThat(jdbc.sql("SELECT customer_notified_at IS NOT NULL FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id").param("id", UUID.fromString(id)).query(Boolean.class).single()).isTrue();
        assertThat(fixtures.outboxCount("ScheduledOccurrenceRetryNotified")).isEqualTo(notices + 1);
        assertThat(worker.runCycle().started()).as("antes da janela não retenta").isZero();

        clock.set(Instant.parse("2026-10-16T23:59:00Z"));                                  // 20:59 em Brasília, ainda no dia
        assertThat(worker.runCycle().started()).isEqualTo(1);
        processReservation.process(jdbc.sql("SELECT id FROM ledger_reservation WHERE idempotency_key = :k").param("k", e2eOf(orderOf(id))).query(UUID.class).single());
        worker.runCycle();

        assertThat(occurrenceStatus(id)).isEqualTo("FAILED_INSUFFICIENT_FUNDS");
        assertThat(scheduleStatus(id)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Falha de negócio (chave inexistente) ⇒ FAILED sem ordem e sem retry; a SEGUINTE da recorrência continua (BR-SCH-05)")
    void shouldFailBusinessErrorsAndKeepTheNextOccurrences() throws Exception {
        String id = create(body("WEEKLY", "2026-10-16", ",\"occurrencesTotal\":2", "50.00")).get("id").asText();
        DictGatewayStub.lookupNotFound();

        clock.set(Instant.parse(DUE_NOON));
        var cycleResult = worker.runCycle();

        assertThat(cycleResult.failed()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status || ':' || last_error_code FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id AND sequence_no = 1").param("id", UUID.fromString(id)).query(String.class).single()).isEqualTo("FAILED:PIX-422-012");
        assertThat(jdbc.sql("SELECT payment_order_id IS NULL FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id AND sequence_no = 1").param("id", UUID.fromString(id)).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT status FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id AND sequence_no = 2").param("id", UUID.fromString(id)).query(String.class).single()).isEqualTo("SCHEDULED");
        assertThat(scheduleStatus(id)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Falha técnica (DICT 5xx) volta a aguardar sem contar tentativa nem avisar; sistema parado por dias ⇒ MISSED, não paga fora de hora")
    void shouldRetryTechnicalFailuresAndNotPayWhenTooLate() throws Exception {
        String id = create(body("ONCE", "2026-10-16", "", "50.00")).get("id").asText();
        DictGatewayStub.SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/dict/v1/entries/lookup"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(500)));

        clock.set(Instant.parse(DUE_NOON));
        worker.runCycle();
        assertThat(occurrenceStatus(id)).isEqualTo("SCHEDULED");
        assertThat(jdbc.sql("SELECT attempt_count FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id").param("id", UUID.fromString(id)).query(Integer.class).single()).isZero();
        assertThat(worker.runCycle().started()).as("respeita o backoff").isZero();

        clock.set(Instant.parse("2026-10-20T15:00:00Z"));                                  // 4 dias depois: passou de maxLateDays (3)
        worker.runCycle();
        assertThat(jdbc.sql("SELECT status || ':' || last_error_code FROM scheduled_payment_occurrence WHERE scheduled_payment_id = :id").param("id", UUID.fromString(id)).query(String.class).single()).isEqualTo("FAILED:MISSED");
    }
}
