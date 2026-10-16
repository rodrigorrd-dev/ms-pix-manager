package br.com.pixmanager.api.rest.charge.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdditionalInfoRequest(
        @JsonProperty("nome") @NotBlank @Size(max = 50) String name,
        @JsonProperty("valor") @NotBlank @Size(max = 200) String value) {}
