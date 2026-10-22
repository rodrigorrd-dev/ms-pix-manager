package br.com.pixmanager.core.application.service.receivedpix;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.ValueComponent;
import br.com.pixmanager.core.domain.enums.ComponentType;
import br.com.pixmanager.core.domain.enums.LedgerStatus;
import br.com.pixmanager.core.port.in.ledger.ProcessReservationUseCase;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase.ReceivePixCommand;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase.ReceivePixResult;
import br.com.pixmanager.core.port.in.refund.SettleRefundUseCase;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut;
import br.com.pixmanager.core.port.out.ledger.LedgerGatewayPortOut.GatewayResult;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.support.TestDocuments;
import br.com.pixmanager.support.integration.AbstractApplicationIT;
import br.com.pixmanager.support.integration.AccountFixtures.Account;
import br.com.pixmanager.support.integration.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Pix recebido → conciliação com a cobrança → devolução (reservar → confirmar|cancelar)")
class ReceivePixAndRefundIT extends AbstractApplicationIT {

    @Autowired ReceivePixUseCase receivePix;
    @Autowired ProcessReservationUseCase processReservation;
    @Autowired SettleRefundUseCase settleRefund;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean LedgerGatewayPortOut ledgerGateway;

    Account account;
    String pixKey;

    @BeforeEach
    void seed() {
        account = fixtures.account(TestDocuments.randomCpf(), "Recebedor");
        pixKey = UUID.randomUUID().toString();
        fixtures.activeKey(account.id(), "EVP", pixKey);
        when(ledgerGateway.reserve(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(ledgerGateway.confirm(any())).thenReturn(new GatewayResult.Done("L-1"));
        when(ledgerGateway.cancel(any())).thenReturn(new GatewayResult.Done("L-1"));
    }

    String e2e() {
        return "E12345678202610151403" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    ReceivePixCommand command(String messageId, String e2eId, String txid, String amount, Instant settledAt) {
        return new ReceivePixCommand("it-group", messageId, "pix.spi.payment-settled.v1", 0, 1, e2eId, txid, Money.of(amount), settledAt,
                "12345678", "0001", account.number(), "CACC", "11144477735", "Maria Pagadora", "87654321", "0002", "99887", "CACC",
                "pagamento teste", List.of(new ValueComponent(ComponentType.ORIGINAL, Money.of(amount), null, null)));
    }

    String createCharge(String amount, int expirationSeconds) throws Exception {
        String txid = UUID.randomUUID().toString().replace("-", "");
        mvc.perform(put("/api/pix/v2/cob/{txid}", txid).with(TestJwt.client("cli", List.of(account.id()), "cob.write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calendario\":{\"expiracao\":%d},\"valor\":{\"original\":\"%s\"},\"chave\":\"%s\"}".formatted(expirationSeconds, amount, pixKey)))
                .andExpect(status().isCreated());
        return txid;
    }

    String chargeStatus(String txid) {
        return jdbc.sql("SELECT status FROM pix_charge WHERE txid = :t").param("t", txid).query(String.class).single();
    }

    String reconciliation(String e2eId) {
        return jdbc.sql("SELECT reconciliation_status FROM pix_received WHERE end_to_end_id = :e").param("e", e2eId).query(String.class).single();
    }

    @Test
    @DisplayName("BR-PIX-02: Pix com txid igual ao da cobrança → MATCHED, cobrança CONCLUIDA, eventos PixReceived e ChargePaid")
    void shouldReconcileAndPublishEvents() throws Exception {
        String txid = createCharge("150.00", 3600);
        String e2eId = e2e();
        int pixEvents = fixtures.outboxCount("PixReceived");
        int paidEvents = fixtures.outboxCount("ChargePaid");

        var result = receivePix.receive(command("m1", e2eId, txid, "150.00", Instant.now()));

        assertThat(result).isInstanceOf(ReceivePixResult.Received.class);
        assertThat(reconciliation(e2eId)).isEqualTo("MATCHED");
        assertThat(chargeStatus(txid)).isEqualTo("CONCLUIDA");
        assertThat(fixtures.outboxCount("PixReceived")).isEqualTo(pixEvents + 1);
        assertThat(fixtures.outboxCount("ChargePaid")).isEqualTo(paidEvents + 1);
    }

    @Test
    @DisplayName("BR-PIX-01/GEN-02: mesma mensagem 2× (inbox) e mesmo e2eId em outra mensagem ⇒ 1 efeito só")
    void shouldBeIdempotentByInboxAndEndToEndId() throws Exception {
        String txid = createCharge("20.00", 3600);
        String e2eId = e2e();

        assertThat(receivePix.receive(command("dup-1", e2eId, txid, "20.00", Instant.now()))).isInstanceOf(ReceivePixResult.Received.class);
        assertThat(receivePix.receive(command("dup-1", e2eId, txid, "20.00", Instant.now()))).isInstanceOf(ReceivePixResult.Duplicate.class);
        assertThat(receivePix.receive(command("dup-2", e2eId, txid, "20.00", Instant.now()))).isInstanceOf(ReceivePixResult.Duplicate.class);

        assertThat(jdbc.sql("SELECT count(*) FROM pix_received WHERE end_to_end_id = :e").param("e", e2eId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("BR-PIX-03: valor diferente ⇒ MISMATCH; depois de expirar ⇒ MATCHED_AFTER_EXPIRATION; sem cobrança ⇒ NO_CHARGE (o Pix nunca é rejeitado)")
    void shouldNeverRejectALiquidatedPix() throws Exception {
        String firstTxid = createCharge("50.00", 3600);
        String firstEndToEndId = e2e();
        receivePix.receive(command("mm-1", firstEndToEndId, firstTxid, "49.00", Instant.now()));
        assertThat(reconciliation(firstEndToEndId)).isEqualTo("MISMATCH");
        assertThat(chargeStatus(firstTxid)).isEqualTo("ATIVA");

        String secondTxid = createCharge("50.00", 60);
        String secondEndToEndId = e2e();
        receivePix.receive(command("exp-1", secondEndToEndId, secondTxid, "50.00", Instant.now().plusSeconds(86_400)));
        assertThat(reconciliation(secondEndToEndId)).isEqualTo("MATCHED_AFTER_EXPIRATION");
        assertThat(chargeStatus(secondTxid)).isEqualTo("ATIVA");

        String thirdEndToEndId = e2e();
        receivePix.receive(command("nc-1", thirdEndToEndId, null, "10.00", Instant.now()));
        assertThat(reconciliation(thirdEndToEndId)).isEqualTo("NO_CHARGE");

        // BR-COB-10: segundo Pix para cobrança já concluída vira "sem cobrança vinculada"
        String fourthTxid = createCharge("5.00", 3600);
        receivePix.receive(command("p4a", e2e(), fourthTxid, "5.00", Instant.now()));
        String e4b = e2e();
        receivePix.receive(command("p4b", e4b, fourthTxid, "5.00", Instant.now()));
        assertThat(reconciliation(e4b)).isEqualTo("NO_CHARGE");
    }

    @Test
    @DisplayName("Conta recebedora desconhecida ⇒ NotFoundException (negócio: o consumidor manda direto à DLT, sem retry)")
    void unknownReceiverAccountIsABusinessFailure() {
        ReceivePixCommand cmd = command("u1", e2e(), null, "1.00", Instant.now());
        ReceivePixCommand unknown = new ReceivePixCommand(cmd.consumerGroup(), "u2", cmd.topic(), 0, 2, e2e(), null, cmd.amount(),
                cmd.settledAt(), "12345678", "0001", "000000", "CACC", null, null, null, null, null, null, null, List.of());
        assertThatThrownBy(() -> receivePix.receive(unknown)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Devolução: 201 → replay 200 (mesmo recurso) → corpo diferente 422 → teto devolvível 422 → pacs.004 liquidado confirma no core")
    void shouldRefundWithIdempotencyCapAndSettlement() throws Exception {
        String e2eId = e2e();
        receivePix.receive(command("r1", e2eId, null, "100.00", Instant.now()));
        var jwt = TestJwt.client("cli", List.of(account.id()), "pix.write", "pix.read");

        // 1ª vez
        mvc.perform(put("/api/pix/v2/pix/{e2e}/devolucao/{id}", e2eId, "D1").with(jwt).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":\"60.00\",\"descricao\":\"estorno\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("D1")).andExpect(jsonPath("$.status").value("EM_PROCESSAMENTO"))
                .andExpect(jsonPath("$.rtrId").value(org.hamcrest.Matchers.hasLength(32)));
        // replay idêntico: 200, sem nova reserva
        mvc.perform(put("/api/pix/v2/pix/{e2e}/devolucao/{id}", e2eId, "D1").with(jwt).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":\"60.00\",\"descricao\":\"estorno\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_reservation WHERE purpose='REFUND' AND reference_type='REFUND'").query(Integer.class).single()).isGreaterThanOrEqualTo(1);
        // mesmo id, corpo diferente
        mvc.perform(put("/api/pix/v2/pix/{e2e}/devolucao/{id}", e2eId, "D1").with(jwt).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":\"61.00\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-900"));
        // excede o devolvível (60 + 50 > 100)
        mvc.perform(put("/api/pix/v2/pix/{e2e}/devolucao/{id}", e2eId, "D2").with(jwt).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":\"50.00\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PIX-422-030"));
        // cabe (60 + 40 = 100)
        mvc.perform(put("/api/pix/v2/pix/{e2e}/devolucao/{id}", e2eId, "D3").with(jwt).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":\"40.00\"}")).andExpect(status().isCreated());
        // natureza MED não é permitida por esta porta
        mvc.perform(put("/api/pix/v2/pix/{e2e}/devolucao/{id}", e2eId, "D4").with(jwt).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":\"1.00\",\"natureza\":\"MED_FRAUDE\"}")).andExpect(status().isUnprocessableEntity());

        String rtrId = jdbc.sql("SELECT rtr_id FROM pix_refund WHERE refund_id='D1' AND end_to_end_id=:e").param("e", e2eId).query(String.class).single();
        UUID reservationId = jdbc.sql("SELECT id FROM ledger_reservation WHERE idempotency_key=:k").param("k", rtrId).query(UUID.class).single();
        int reserved = fixtures.outboxCount("RefundReserved");
        processReservation.process(reservationId);                                  // sweeper: reserva no core + RefundReserved
        assertThat(fixtures.outboxCount("RefundReserved")).isEqualTo(reserved + 1);
        assertThat(jdbc.sql("SELECT status FROM ledger_reservation WHERE id=:id").param("id", reservationId).query(String.class).single())
                .isEqualTo(LedgerStatus.RESERVED.name());

        settleRefund.apply("it-refund", "s-1", "pix.spi.refund-status.v1", 0, 1, rtrId, true, null, Instant.now());
        settleRefund.apply("it-refund", "s-1", "pix.spi.refund-status.v1", 0, 1, rtrId, true, null, Instant.now());   // duplicata
        assertThat(jdbc.sql("SELECT status FROM pix_refund WHERE rtr_id=:r").param("r", rtrId).query(String.class).single()).isEqualTo("DEVOLVIDO");
        assertThat(jdbc.sql("SELECT status FROM ledger_reservation WHERE id=:id").param("id", reservationId).query(String.class).single())
                .isEqualTo(LedgerStatus.CONFIRMED.name());

        // GET /pix/{e2e} mostra as devoluções
        mvc.perform(get("/api/pix/v2/pix/{e2e}", e2eId).with(jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.devolucoes.length()").value(2))
                .andExpect(jsonPath("$.pagador.cpf").value("***.444.777-**"));
    }

    @Test
    @DisplayName("BR-REF-07: o core recusa a reserva ⇒ devolução NAO_REALIZADO e o valor devolvível é liberado (compensação)")
    void shouldCompensateWhenCoreRefuses() throws Exception {
        String e2eId = e2e();
        receivePix.receive(command("c1", e2eId, null, "100.00", Instant.now()));
        var jwt = TestJwt.client("cli", List.of(account.id()), "pix.write");
        mvc.perform(put("/api/pix/v2/pix/{e2e}/devolucao/{id}", e2eId, "X1").with(jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"valor\":\"100.00\"}")).andExpect(status().isCreated());
        when(ledgerGateway.reserve(any())).thenReturn(new GatewayResult.Refused(GatewayResult.Reason.INSUFFICIENT_FUNDS, "sem saldo"));

        String rtrId = jdbc.sql("SELECT rtr_id FROM pix_refund WHERE refund_id='X1' AND end_to_end_id=:e").param("e", e2eId).query(String.class).single();
        UUID reservationId = jdbc.sql("SELECT id FROM ledger_reservation WHERE idempotency_key=:k").param("k", rtrId).query(UUID.class).single();
        processReservation.process(reservationId);

        assertThat(jdbc.sql("SELECT status FROM pix_refund WHERE rtr_id=:r").param("r", rtrId).query(String.class).single()).isEqualTo("NAO_REALIZADO");
        assertThat(jdbc.sql("SELECT refunded_total FROM pix_received WHERE end_to_end_id=:e").param("e", e2eId).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("0");
        // liberado: nova devolução do valor cheio é aceita
        mvc.perform(put("/api/pix/v2/pix/{e2e}/devolucao/{id}", e2eId, "X2").with(jwt).contentType(MediaType.APPLICATION_JSON)
                .content("{\"valor\":\"100.00\"}")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Posse: devolução de Pix de OUTRA conta ⇒ 404")
    void shouldHideOtherAccountsPix() throws Exception {
        String e2eId = e2e();
        receivePix.receive(command("o1", e2eId, null, "10.00", Instant.now()));
        mvc.perform(put("/api/pix/v2/pix/{e2e}/devolucao/{id}", e2eId, "Z1")
                        .with(TestJwt.client("outro", List.of(UUID.randomUUID()), "pix.write")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":\"1.00\"}"))
                .andExpect(status().isNotFound());
        assertThat(Caller.forAccounts("c", account.id()).canAccess(account.id())).isTrue();
    }
}
