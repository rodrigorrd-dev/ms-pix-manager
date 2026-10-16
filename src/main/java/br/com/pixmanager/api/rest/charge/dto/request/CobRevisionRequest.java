package br.com.pixmanager.api.rest.charge.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import br.com.pixmanager.core.domain.enums.ChargeStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /cob/{txid}}: remoção ({@code status = REMOVIDA_PELO_USUARIO_RECEBEDOR}) ou revisão parcial — só o que veio
 * preenchido muda. Exige {@code If-Match} (428 se ausente).
 */
public record CobRevisionRequest(
        @JsonProperty("status") ChargeStatus status,
        @JsonProperty("calendario") @Valid CobRequest.Calendar calendar,
        @JsonProperty("devedor") @Valid DebtorRequest debtor,
        @JsonProperty("valor") @Valid ValueRequest value,
        @JsonProperty("solicitacaoPagador") @Size(max = 140) String payerRequest) {}
