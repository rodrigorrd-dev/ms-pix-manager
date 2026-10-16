package br.com.pixmanager.api.rest.webhook.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record WebhookResponse(@JsonProperty("webhookUrl") String webhookUrl, @JsonProperty("chave") String key,
                              @JsonProperty("criacao") Instant created) {}
