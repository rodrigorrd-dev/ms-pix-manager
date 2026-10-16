package br.com.pixmanager.api.rest.refund.dto.request;

import br.com.pixmanager.core.domain.enums.RefundNature;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code PUT /pix/{e2eid}/devolucao/{id}}. Campos em português = contrato do BCB. */
public record RefundRequest(
        @JsonProperty("valor") @NotBlank @Pattern(regexp = "\\d{1,10}\\.\\d{2}") String amount,
        @JsonProperty("natureza") RefundNature nature,
        @JsonProperty("descricao") @Size(max = 140) String description) {}
