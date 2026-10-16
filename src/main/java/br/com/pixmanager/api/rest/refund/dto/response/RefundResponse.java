package br.com.pixmanager.api.rest.refund.dto.response;

import br.com.pixmanager.core.domain.enums.RefundNature;
import br.com.pixmanager.core.domain.enums.RefundStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record RefundResponse(
        @JsonProperty("id") String id,
        @JsonProperty("rtrId") String rtrId,
        @JsonProperty("valor") String amount,
        @JsonProperty("natureza") RefundNature nature,
        @JsonProperty("descricao") String description,
        @JsonProperty("horario") Times times,
        @JsonProperty("status") RefundStatus status,
        @JsonProperty("motivo") String reason) {

    public record Times(@JsonProperty("solicitacao") Instant requested, @JsonProperty("liquidacao") Instant settled) {}
}
