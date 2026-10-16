package br.com.pixmanager.api.rest.charge.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** {@code PUT /cobv/{txid}}: cobrança com vencimento; o BCB exige devedor identificado. */
public record CobvRequest(
        @JsonProperty("calendario") @NotNull @Valid Calendar calendar,
        @JsonProperty("devedor") @NotNull @Valid DebtorRequest debtor,
        @JsonProperty("valor") @NotNull @Valid ValueRequest value,
        @JsonProperty("chave") @NotBlank @Size(max = 77) String pixKey,
        @JsonProperty("solicitacaoPagador") @Size(max = 140) String payerRequest,
        @JsonProperty("infoAdicionais") @Size(max = 50) List<@Valid AdditionalInfoRequest> additionalInfo) {

    public record Calendar(
            @JsonProperty("dataDeVencimento") @NotNull LocalDate dueDate,
            @JsonProperty("validadeAposVencimento") @Min(0) Integer validityAfterDueDays) {}
}
