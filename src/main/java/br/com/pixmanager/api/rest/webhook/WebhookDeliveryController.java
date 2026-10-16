package br.com.pixmanager.api.rest.webhook;

import br.com.pixmanager.api.rest.security.ScopeAuthorization;
import br.com.pixmanager.api.rest.webhook.dto.response.WebhookDeliveryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

/** Reenvio manual de entrega DEAD (back-office). Transição substantiva ({@code /resend}), não PATCH genérico. */
@RequestMapping("/v1/webhook-deliveries")
public interface WebhookDeliveryController {

    @PostMapping("/{id}/resend")
    @PreAuthorize(ScopeAuthorization.WEBHOOK_WRITE)
    ResponseEntity<WebhookDeliveryResponse> resend(@PathVariable UUID id);
}
