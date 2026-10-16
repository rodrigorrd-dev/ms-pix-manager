package br.com.pixmanager.api.rest.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DecodeBrCodeRequest(@NotBlank @Size(max = 4096) String payload) {}
