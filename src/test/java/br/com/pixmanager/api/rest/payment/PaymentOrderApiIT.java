package br.com.pixmanager.api.rest.payment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import br.com.pixmanager.core.port.in.ledger.ProcessReservationUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase;
import br.com.pixmanager.core.port.in.payment.ApplyPaymentStatusUseCase.SpiStatus;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut.GatewayResult;
import br.com.pixmanager.support.BrCodeFactory;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.TestDocuments;
import br.com.pixmanager.support.integration.AbstractApplicationIT;
import br.com.pixmanager.support.integration.AccountFixtures.Account;
import br.com.pixmanager.support.integration.DictGatewayStub;
import br.com.pixmanager.support.integration.QrReceiverStub;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Ordens de pagamento Pix: DICT → limite → reserva no core → SPI; idempotência, recusas e cancelamento")
@Import(PaymentOrderApiIT.DaytimeClock.class)
class PaymentOrderApiIT extends AbstractApplicationIT {

    /**
     * O limite NOTURNO (20h–6h de Brasília, R$ 1.000 por transação) depende do relógio. Sem fixá-lo, estes testes falhavam quando rodados à noite
     * (valores de R$ 4.000 davam 422). O relógio começa SEMPRE em horário comercial; o teste noturno o move de propósito.
     */
    @TestConfiguration
    static class DaytimeClock {
        @Bean @Primary FixedClock testClock() {
            return FixedClock.at(DAYTIME);
        }
    }

    static final String DAYTIME = "2026-10-15T15:00:00Z";      // 12:00 em Brasília
    static final String NIGHT = "2026-10-16T02:00:00Z";        // 23:00 em Brasília

    @Autowired FixedClock clock;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ProcessReservationUseCase processReservation;
    @Autowired ApplyPaymentStatusUseCase applyStatus;
    @MockitoBean LedgerGatewayPortOut ledger;

    Account payer;
    String ownerCpf;

    @BeforeEach
    void setUp() {
        clock.set(java.time.Instant.parse(DAYTIME));
        DictGatewayStub.reset();
        reset(ledger);
        when(ledger.reserve(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(ledger.confirm(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(ledger.cancel(any())).thenReturn(new GatewayResult.Done("L-1"));
        payer = fixtures.account(TestDocuments.randomCpf(), "Pagador");
        ownerCpf = TestDocuments.randomCpf();
        DictGatewayStub.lookupFound(ownerCpf);
        fixtures.trustedDevice(payer.id(), "device-ok");
    }

    org.springframework.test.web.servlet.request.RequestPostProcessor jwt(String... scopes) {
        return TestJwt.client("cli", List.of(payer.id()), scopes);
    }

    String byKey(String amount, String keyHint, String device) {
        return """
                {"payerAccountId":"%s","method":"KEY","amount":"%s","payerMessage":"aluguel",
                 "destination":{"pixKeyType":"EMAIL","pixKey":"alguem@exemplo.com.br"%s}%s}
                """.formatted(payer.id(), amount, keyHint == null ? "" : ",\"documentHint\":\"" + keyHint + "\"",
                device == null ? "" : ",\"deviceId\":\"" + device + "\"");
    }

    JsonNode create(String body) throws Exception {
        MvcResult mvcResult = mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(mvcResult.getResponse().getStatus()).as("corpo da resposta: %s", mvcResult.getResponse().getContentAsString()).isEqualTo(201);
        return json.readTree(mvcResult.getResponse().getContentAsString());
    }

    UUID reservationOf(String endToEndId) {
        return jdbc.sql("SELECT id FROM ledger_reservation WHERE idempotency_key = :k").param("k", endToEndId).query(UUID.class).single();
    }

    String orderStatus(String id) {
        return jdbc.sql("SELECT status FROM payment_order WHERE id = :id").param("id", UUID.fromString(id)).query(String.class).single();
    }

    @Test
    @DisplayName("Fluxo completo: 201 VALIDATED (e2eId gerado, favorecido MASCARADO, limite consumido, reserva PENDING) → reservado no core → pacs.002 SETTLED confirma no core")
    void shouldRunTheWholePaymentFlow() throws Exception {
        int created = fixtures.outboxCount("PaymentOrderCreated");
        JsonNode order = create(byKey("250.00", ownerCpf, "device-ok"));
        String endToEndId = order.get("endToEndId").asText();

        assertThat(order.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(endToEndId).hasSize(32).startsWith("E12345678");
        assertThat(order.get("destinationName").asText()).isEqualTo("Joao Favorecido");
        assertThat(order.get("destinationDocumentMasked").asText()).startsWith("***.").doesNotContain(ownerCpf);
        assertThat(fixtures.outboxCount("PaymentOrderCreated")).isEqualTo(created + 1);
        assertThat(fixtures.dailyUsage(payer.id(), "DAILY")).isEqualByComparingTo("250.00");
        assertThat(jdbc.sql("SELECT status FROM ledger_reservation WHERE idempotency_key = :k").param("k", endToEndId).query(String.class).single()).isEqualTo("PENDING");
        verify(ledger, never()).reserve(any());                      // a chamada ao core NÃO acontece dentro da transação do pedido

        int reservedEvents = fixtures.outboxCount("PaymentReserved");
        processReservation.process(reservationOf(endToEndId));              // sweeper: reservar no core
        assertThat(orderStatus(order.get("id").asText())).isEqualTo("RESERVED");
        assertThat(fixtures.outboxCount("PaymentReserved")).isEqualTo(reservedEvents + 1);

        applyStatus.apply("it", "s1", "pix.spi.payment-status.v1", 0, 1, endToEndId, SpiStatus.SENT, null, Instant.now());
        assertThat(orderStatus(order.get("id").asText())).isEqualTo("SENT_TO_SPI");
        applyStatus.apply("it", "s2", "pix.spi.payment-status.v1", 0, 2, endToEndId, SpiStatus.SETTLED, null, Instant.now());
        applyStatus.apply("it", "s2", "pix.spi.payment-status.v1", 0, 2, endToEndId, SpiStatus.SETTLED, null, Instant.now());   // duplicata
        assertThat(orderStatus(order.get("id").asText())).isEqualTo("SETTLED");
        assertThat(jdbc.sql("SELECT status FROM ledger_reservation WHERE idempotency_key = :k").param("k", endToEndId).query(String.class).single()).isEqualTo("CONFIRMED");
        assertThat(fixtures.dailyUsage(payer.id(), "DAILY")).as("pagamento liquidado mantém o consumo").isEqualByComparingTo("250.00");

        mvc.perform(get("/v1/payment-orders/{id}", order.get("id").asText()).with(jwt("payment.read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED")).andExpect(header().exists("ETag"));
    }

    @Test
    @DisplayName("Idempotency-Key: replay devolve a MESMA ordem (header Idempotent-Replayed) e consome o limite UMA vez; corpo diferente ⇒ 422")
    void shouldBeIdempotentAndConsumeTheLimitOnce() throws Exception {
        String key = "pay-" + UUID.randomUUID();
        String body = byKey("100.00", null, "device-ok");
        MvcResult first = mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).get("id"))
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()).get("id"));
        assertThat(jdbc.sql("SELECT count(*) FROM payment_order WHERE payer_account_id = :a").param("a", payer.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(fixtures.dailyUsage(payer.id(), "DAILY")).isEqualByComparingTo("100.00");

        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(byKey("101.00", null, "device-ok")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-900"));
        // sem a chave: 400
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Limites: diário PF R$5.000 ⇒ 422 PIX-422-050, ordem REJECTED gravada e o replay devolve o MESMO 422 sem consumir de novo")
    void shouldRejectOverDailyLimitAndReplayTheRejection() throws Exception {
        create(byKey("4000.00", null, "device-ok"));
        String key = "over-" + UUID.randomUUID();
        String body = byKey("1000.01", null, "device-ok");
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PIX-422-050")).andExpect(jsonPath("$.paymentOrderId").exists());
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(jdbc.sql("SELECT count(*) FROM payment_order WHERE payer_account_id = :a AND status = 'REJECTED'").param("a", payer.id()).query(Integer.class).single()).isEqualTo(1);
        assertThat(fixtures.dailyUsage(payer.id(), "DAILY")).isEqualByComparingTo("4000.00");
        // exatamente no limite passa
        create(byKey("1000.00", null, "device-ok"));
        assertThat(fixtures.dailyUsage(payer.id(), "DAILY")).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("Noturno (20h–6h Brasília): PF tem R$ 1.000 por transação; o mesmo valor passa de dia")
    void shouldApplyTheNightlyLimitOnlyAtNight() throws Exception {
        clock.set(java.time.Instant.parse(NIGHT));
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", "n1-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(byKey("1000.01", null, "device-ok")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-050"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("NIGHTLY")));
        create(byKey("1000.00", null, "device-ok"));                       // exatamente no limite passa à noite
        clock.set(java.time.Instant.parse(DAYTIME));
        create(byKey("1000.01", null, "device-ok"));                       // de dia o limite noturno não vale
    }

    @Test
    @DisplayName("Dispositivo NÃO cadastrado (ou ausente): R$200 por transação; cadastrado passa")
    void shouldApplyUnregisteredDeviceLimits() throws Exception {
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", "d1-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(byKey("200.01", null, "device-desconhecido")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-050"));
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", "d2-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(byKey("200.01", null, null)))
                .andExpect(status().isUnprocessableEntity());
        create(byKey("200.00", null, "device-desconhecido"));
        create(byKey("300.00", null, "device-ok"));
    }

    @Test
    @DisplayName("BR-REF-07 análogo: o core recusa por saldo ⇒ ordem REJECTED com AM04 e o LIMITE consumido é devolvido")
    void shouldCompensateLimitWhenTheCoreRefuses() throws Exception {
        JsonNode order = create(byKey("900.00", null, "device-ok"));
        String endToEndId = order.get("endToEndId").asText();
        when(ledger.reserve(any())).thenReturn(new GatewayResult.Refused(GatewayResult.Reason.INSUFFICIENT_FUNDS, "sem saldo"));

        processReservation.process(reservationOf(endToEndId));

        assertThat(orderStatus(order.get("id").asText())).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT reject_reason_code FROM payment_order WHERE id = :id").param("id", UUID.fromString(order.get("id").asText())).query(String.class).single()).isEqualTo("AM04");
        assertThat(fixtures.dailyUsage(payer.id(), "DAILY")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("SPI rejeita (pacs.002 AB03): cancela a reserva no core, REJECTED com o código ISO e limite devolvido")
    void shouldRejectOnSpiAndGiveEverythingBack() throws Exception {
        JsonNode order = create(byKey("500.00", null, "device-ok"));
        String endToEndId = order.get("endToEndId").asText();
        processReservation.process(reservationOf(endToEndId));
        applyStatus.apply("it", "r1", "pix.spi.payment-status.v1", 0, 5, endToEndId, SpiStatus.REJECTED, "AB03", Instant.now());

        assertThat(orderStatus(order.get("id").asText())).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT reject_reason_code FROM payment_order WHERE id = :id").param("id", UUID.fromString(order.get("id").asText())).query(String.class).single()).isEqualTo("AB03");
        assertThat(jdbc.sql("SELECT status FROM ledger_reservation WHERE idempotency_key = :k").param("k", endToEndId).query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(fixtures.dailyUsage(payer.id(), "DAILY")).isEqualByComparingTo("0");
        verify(ledger, times(1)).cancel(any());
    }

    @Test
    @DisplayName("Cancelamento: sem If-Match ⇒ 428; antes do SPI ⇒ CANCELLED (limite devolvido); depois de SENT_TO_SPI ⇒ 422")
    void shouldCancelOnlyBeforeTheSpi() throws Exception {
        JsonNode order = create(byKey("120.00", null, "device-ok"));
        String id = order.get("id").asText();
        mvc.perform(post("/v1/payment-orders/{id}/cancellation", id).with(jwt("payment.write"))).andExpect(status().isPreconditionRequired());
        mvc.perform(post("/v1/payment-orders/{id}/cancellation", id).with(jwt("payment.write")).header("If-Match", "\"77\""))
                .andExpect(status().isPreconditionFailed());
        long version = jdbc.sql("SELECT version FROM payment_order WHERE id = :id").param("id", UUID.fromString(id)).query(Long.class).single();
        mvc.perform(post("/v1/payment-orders/{id}/cancellation", id).with(jwt("payment.write")).header("If-Match", "\"" + version + "\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(fixtures.dailyUsage(payer.id(), "DAILY")).isEqualByComparingTo("0");

        JsonNode sent = create(byKey("30.00", null, "device-ok"));
        processReservation.process(reservationOf(sent.get("endToEndId").asText()));
        applyStatus.apply("it", "c1", "pix.spi.payment-status.v1", 0, 9, sent.get("endToEndId").asText(), SpiStatus.SENT, null, Instant.now());
        long currentVersion = jdbc.sql("SELECT version FROM payment_order WHERE id = :id").param("id", UUID.fromString(sent.get("id").asText())).query(Long.class).single();
        mvc.perform(post("/v1/payment-orders/{id}/cancellation", sent.get("id").asText()).with(jwt("payment.write")).header("If-Match", "\"" + currentVersion + "\""))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("BR-PAY-04: documento esperado diferente do dono da chave ⇒ 422 PIX-422-040 e NADA é gravado nem consumido")
    void shouldRejectDestinationMismatch() throws Exception {
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", "mm-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(byKey("10.00", TestDocuments.randomCpf(), "device-ok")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-040"));
        assertThat(jdbc.sql("SELECT count(*) FROM payment_order WHERE payer_account_id = :a").param("a", payer.id()).query(Integer.class).single()).isZero();
        assertThat(fixtures.dailyUsage(payer.id(), "DAILY")).isEqualByComparingTo("0");
        // documentHint inválido (dígito verificador) ⇒ 422 de CPF
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", "mm2-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(byKey("10.00", "11111111111", "device-ok")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-001"))
                .andExpect(content().string(not(containsString("11111111111"))));
    }

    @Test
    @DisplayName("Pagamento MANUAL valida CPF/CNPJ do favorecido e ISPB")
    void shouldValidateManualDestination() throws Exception {
        String ok = """
                {"payerAccountId":"%s","method":"MANUAL","amount":"50.00","deviceId":"device-ok",
                 "destination":{"ispb":"87654321","branch":"0002","account":"12345","accountType":"CACC","name":"Maria","document":"%s"}}
                """.formatted(payer.id(), TestDocuments.randomCpf());
        create(ok);
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", "mn1-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(ok.replaceAll("\"document\":\"\\d+\"", "\"document\":\"12345678900\"")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-001"));
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", "mn2-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(ok.replace("87654321", "123")))
                .andExpect(status().isBadRequest());
        String legalEntityBody = ok.replaceAll("\"document\":\"\\d+\"", "\"document\":\"" + TestDocuments.randomCnpj() + "\"");
        create(legalEntityBody);
    }

    @Test
    @DisplayName("QR estático: decodifica (CRC), paga com o valor do QR; CRC inválido e QR dinâmico fica em outro teste")
    void shouldPayAndDecodeStaticQr() throws Exception {
        String staticQr = BrCodeFactory.staticQr("alguem@exemplo.com.br", "75.90", "Loja do Joao", "SAO PAULO", "PED123");
        mvc.perform(post("/v1/payment-orders/decodings").with(jwt("payment.read")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"" + staticQr + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value("75.90")).andExpect(jsonPath("$.dynamic").value(false))
                .andExpect(jsonPath("$.pixKeyMasked").value(not(containsString("alguem@"))));
        JsonNode order = create("{\"payerAccountId\":\"%s\",\"method\":\"QR_STATIC\",\"qrPayload\":\"%s\",\"deviceId\":\"device-ok\"}".formatted(payer.id(), staticQr));
        assertThat(order.get("amount").asText()).isEqualTo("75.90");
        // valor informado diferente do QR
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", "q1-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"payerAccountId\":\"%s\",\"method\":\"QR_STATIC\",\"amount\":\"10.00\",\"qrPayload\":\"%s\",\"deviceId\":\"device-ok\"}".formatted(payer.id(), staticQr)))
                .andExpect(status().isUnprocessableEntity());
        // CRC adulterado
        mvc.perform(post("/v1/payment-orders/decodings").with(jwt("payment.read")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"payload\":\"" + staticQr.replace("75.90", "99.90") + "\"}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("QR dinâmico: paga com o valor do payload ASSINADO; cobrança inativa/divergente/inexistente ⇒ 422 PIX-422-018|PIX-422-003; receptor fora ⇒ 503")
    void shouldPayDynamicQr() throws Exception {
        String key = "alguem@exemplo.com.br";
        QrReceiverStub.publish("/qr/v2/ok", QrReceiverStub.cob("dyn001", key, "31.00", "ATIVA"));
        QrReceiverStub.publish("/qr/v2/paga", QrReceiverStub.cob("dyn002", key, "31.00", "CONCLUIDA"));
        QrReceiverStub.status("/qr/v2/nao", 404);
        QrReceiverStub.status("/qr/v2/fora", 503);
        String body = "{\"payerAccountId\":\"%s\",\"method\":\"QR_DYNAMIC\",\"qrPayload\":\"%s\"%s,\"deviceId\":\"device-ok\"}";
        java.util.function.BiFunction<String, String, String> pay = (path, amount) -> body.formatted(payer.id(),
                BrCodeFactory.dynamicQr(QrReceiverStub.urlFor(path)), amount);

        JsonNode order = create(pay.apply("/qr/v2/ok", ""));
        assertThat(order.get("amount").asText()).isEqualTo("31.00");
        assertThat(order.get("method").asText()).isEqualTo("QR_DYNAMIC");

        for (var entry : java.util.List.of(java.util.Map.entry("/qr/v2/paga", ""), java.util.Map.entry("/qr/v2/nao", ""),
                java.util.Map.entry("/qr/v2/ok", ",\"amount\":\"1.00\""))) {
            mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", "qd-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(pay.apply(entry.getKey(), entry.getValue())))
                    .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", "qd-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(pay.apply("/qr/v2/fora", ""))).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("Segurança: 403 sem escopo; conta de OUTRO cliente ⇒ 404; falha do DICT (5xx) ⇒ 502 e a chave de idempotência NÃO é queimada")
    void shouldEnforceSecurityAndNotBurnTheKeyOnDictFailure() throws Exception {
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.read")).header("Idempotency-Key", "s1")
                .contentType(MediaType.APPLICATION_JSON).content(byKey("1.00", null, "device-ok"))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/payment-orders").with(TestJwt.client("outro", List.of(UUID.randomUUID()), "payment.write")).header("Idempotency-Key", "s2")
                .contentType(MediaType.APPLICATION_JSON).content(byKey("1.00", null, "device-ok"))).andExpect(status().isNotFound());

        String key = "dict-" + UUID.randomUUID();
        DictGatewayStub.reset();
        DictGatewayStub.SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/dict/v1/entries/lookup"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(500)));
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(byKey("5.00", null, "device-ok"))).andExpect(status().isBadGateway());
        DictGatewayStub.reset();
        DictGatewayStub.lookupFound(ownerCpf);
        mvc.perform(post("/v1/payment-orders").with(jwt("payment.write")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(byKey("5.00", null, "device-ok"))).andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT count(*) FROM payment_order WHERE payer_account_id = :a").param("a", payer.id()).query(Integer.class).single()).isEqualTo(1);
    }
}
