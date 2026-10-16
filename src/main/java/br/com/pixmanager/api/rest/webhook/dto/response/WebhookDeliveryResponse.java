package br.com.pixmanager.api.rest.webhook.dto.response;

import br.com.pixmanager.core.domain.enums.DeliveryStatus;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryResponse(UUID id, DeliveryStatus status, int attempts, Instant nextAttemptAt, Integer lastHttpStatus, String lastError) {}
