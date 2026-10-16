package br.com.pixmanager.api.rest.receivedpix.dto.response;

import br.com.pixmanager.api.rest.refund.dto.response.RefundResponse;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/** {@code GET /pix/{e2eid}}: pagador com documento MASCARADO salvo escopo {@code pii.document.read}. */
public record ReceivedPixResponse(
        @JsonProperty("endToEndId") String endToEndId,
        @JsonProperty("txid") String txid,
        @JsonProperty("valor") String amount,
        @JsonProperty("horario") Instant settledAt,
        @JsonProperty("pagador") Payer payer,
        @JsonProperty("infoPagador") String payerInfo,
        @JsonProperty("componentesValor") List<Component> components,
        @JsonProperty("devolucoes") List<RefundResponse> refunds) {

    public record Payer(@JsonProperty("cpf") String cpf, @JsonProperty("cnpj") String cnpj, @JsonProperty("nome") String name) {}

    public record Component(@JsonProperty("tipo") String type, @JsonProperty("valor") String amount) {}
}
