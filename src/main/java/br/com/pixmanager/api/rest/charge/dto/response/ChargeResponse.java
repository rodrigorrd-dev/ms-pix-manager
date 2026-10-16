package br.com.pixmanager.api.rest.charge.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import br.com.pixmanager.core.domain.enums.ChargeStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Resposta de {@code cob}/{@code cobv} (campos do BCB). {@code devedor.cpf/cnpj} sai MASCARADO salvo escopo {@code pii.document.read}. */
public record ChargeResponse(
        @JsonProperty("calendario") Calendar calendar,
        @JsonProperty("txid") String txid,
        @JsonProperty("revisao") int revision,
        @JsonProperty("loc") Location loc,
        @JsonProperty("location") String location,
        @JsonProperty("status") ChargeStatus status,
        @JsonProperty("devedor") Debtor debtor,
        @JsonProperty("valor") Value value,
        @JsonProperty("chave") String pixKey,
        @JsonProperty("solicitacaoPagador") String payerRequest,
        @JsonProperty("infoAdicionais") List<Info> additionalInfo) {

    public record Calendar(
            @JsonProperty("criacao") Instant created,
            @JsonProperty("expiracao") Integer expiration,
            @JsonProperty("dataDeVencimento") LocalDate dueDate,
            @JsonProperty("validadeAposVencimento") Integer validityAfterDueDays) {}

    public record Location(
            @JsonProperty("id") long id,
            @JsonProperty("location") String location,
            @JsonProperty("tipoCob") String chargeType,
            @JsonProperty("criacao") Instant created) {}

    public record Debtor(
            @JsonProperty("cpf") String cpf,
            @JsonProperty("cnpj") String cnpj,
            @JsonProperty("nome") String name) {}

    public record Value(@JsonProperty("original") String original) {}

    public record Info(@JsonProperty("nome") String name, @JsonProperty("valor") String value) {}
}
