package br.com.pixmanager.api.rest.charge.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code cpf} XOR {@code cnpj} (BR-COB-05); o dígito verificador é validado pelo domínio ({@code Cpf}/{@code Cnpj}). */
public record DebtorRequest(
        @JsonProperty("cpf") String cpf,
        @JsonProperty("cnpj") String cnpj,
        @JsonProperty("nome") @NotBlank @Size(max = 200) String name) {}
