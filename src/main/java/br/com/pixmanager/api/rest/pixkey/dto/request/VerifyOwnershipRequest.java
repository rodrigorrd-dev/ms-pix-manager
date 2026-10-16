package br.com.pixmanager.api.rest.pixkey.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Código de 6 dígitos recebido por SMS/e-mail. */
public record VerifyOwnershipRequest(@NotBlank @Pattern(regexp = "\\d{6}", message = "o código tem 6 dígitos") String code) {}
