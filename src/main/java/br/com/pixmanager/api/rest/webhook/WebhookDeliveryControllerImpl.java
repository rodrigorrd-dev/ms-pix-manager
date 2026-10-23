package br.com.pixmanager.api.rest.webhook;

import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.api.rest.webhook.dto.response.WebhookDeliveryResponse;
import br.com.pixmanager.core.domain.entity.WebhookDelivery;
import br.com.pixmanager.core.port.in.webhook.DispatchWebhooksUseCase;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class WebhookDeliveryControllerImpl implements WebhookDeliveryController {

    private final DispatchWebhooksUseCase dispatch;

    WebhookDeliveryControllerImpl(DispatchWebhooksUseCase dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public ResponseEntity<WebhookDeliveryResponse> resend(UUID id) {
        WebhookDelivery delivery = dispatch.resend(CallerContext.current(), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new WebhookDeliveryResponse(delivery.id(), delivery.status(), delivery.attempts(), delivery.nextAttemptAt(), delivery.lastHttpStatus(), delivery.lastError()));
    }
}
