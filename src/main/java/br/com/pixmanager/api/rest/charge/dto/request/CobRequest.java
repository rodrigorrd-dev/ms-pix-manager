package br.com.pixmanager.api.rest.charge.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** {@code PUT/POST /cob}: cobrança imediata. Campos JSON em português = contrato do BCB (API Pix 2.10.0). */
public record CobRequest(
        @JsonProperty("calendario") @Valid Calendar calendar,
        @JsonProperty("devedor") @Valid DebtorRequest debtor,
        @JsonProperty("valor") @NotNull @Valid ValueRequest value,
        @JsonProperty("chave") @NotBlank @Size(max = 77) String pixKey,
        @JsonProperty("solicitacaoPagador") @Size(max = 140) String payerRequest,
        @JsonProperty("infoAdicionais") @Size(max = 50) List<@Valid AdditionalInfoRequest> additionalInfo) {

    /** {@code expiracao} em segundos (padrão 86400 aplicado pelo caso de uso). */
    public record Calendar(@JsonProperty("expiracao") @Min(1) Integer expiration) {}
}
