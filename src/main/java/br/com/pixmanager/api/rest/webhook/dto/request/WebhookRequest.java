package br.com.pixmanager.api.rest.webhook.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corpo do {@code PUT /webhook/{chave}} (API Pix). A URL é a BASE do receptor: o app envia o POST para {@code <webhookUrl>/pix}. */
public record WebhookRequest(@JsonProperty("webhookUrl") @NotBlank @Size(max = 2048) String webhookUrl) {}
