package br.com.pixmanager.api.rest.limit.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Novo valor do limite (string decimal {@code ddd.dd}; nunca number). */
public record ChangeLimitRequest(@NotBlank @Pattern(regexp = "\\d{1,13}\\.\\d{2}") String amount) {}
