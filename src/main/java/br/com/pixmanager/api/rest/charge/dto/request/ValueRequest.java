package br.com.pixmanager.api.rest.charge.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** {@code valor}: o BCB exige string decimal {@code \d{1,10}\.\d{2}} (nunca number: sem perda de precisão). */
public record ValueRequest(
        @JsonProperty("original") @NotBlank @Pattern(regexp = "\\d{1,10}\\.\\d{2}") String original) {}
