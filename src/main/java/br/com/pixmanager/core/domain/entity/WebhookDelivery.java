package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.shared.util.Text;
import br.com.pixmanager.core.domain.enums.DeliveryStatus;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.InvalidTransitionException;

import java.time.Instant;
import java.util.UUID;

/** Entrega de um evento a uma assinatura. 2xx ⇒ DELIVERED; falha ⇒ RETRYING com backoff; esgotou ⇒ DEAD (reenvio manual). */
public record WebhookDelivery(UUID id, UUID subscriptionId, UUID sourceEventId, String eventType, DeliveryStatus status, int attempts,
                              Instant nextAttemptAt, Integer lastHttpStatus, String lastError, Instant deliveredAt, long version) {

    public static WebhookDelivery pending(UUID id, UUID subscriptionId, UUID sourceEventId, String eventType, Instant now) {
        return new WebhookDelivery(id, subscriptionId, sourceEventId, eventType, DeliveryStatus.PENDING, 0, now, null, null, null, 0);
    }

    public boolean isDue(Instant now) {
        return (status == DeliveryStatus.PENDING || status == DeliveryStatus.RETRYING) && !nextAttemptAt.isAfter(now);
    }

    public WebhookDelivery delivered(int httpStatus, Instant now) {
        requireOpen();
        return new WebhookDelivery(id, subscriptionId, sourceEventId, eventType, DeliveryStatus.DELIVERED, attempts + 1, null, httpStatus, null, now, version);
    }

    /** Só 2xx é sucesso; qualquer outro resultado (inclusive 3xx/4xx) conta como falha e é tentado de novo até esgotar. */
    public WebhookDelivery failed(Integer httpStatus, String error, Instant now, WebhookRetryPolicy policy, double random01) {
        requireOpen();
        int made = attempts + 1;
        String trimmed = Text.truncate(error, 500);
        if (policy.exhausted(made)) {
            return new WebhookDelivery(id, subscriptionId, sourceEventId, eventType, DeliveryStatus.DEAD, made, null, httpStatus, trimmed, null, version);
        }
        return new WebhookDelivery(id, subscriptionId, sourceEventId, eventType, DeliveryStatus.RETRYING, made,
                now.plus(policy.delayAfter(made, random01)), httpStatus, trimmed, null, version);
    }

    /** Reenvio manual: só de DEAD; zera as tentativas e volta a PENDING para agora. */
    public WebhookDelivery requeued(Instant now) {
        if (status != DeliveryStatus.DEAD) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "Só entregas DEAD podem ser reenviadas (atual: " + status + ").");
        }
        return new WebhookDelivery(id, subscriptionId, sourceEventId, eventType, DeliveryStatus.PENDING, 0, now, lastHttpStatus, lastError, null, version);
    }

    private void requireOpen() {
        if (status.isFinal()) {
            throw new InvalidTransitionException(ErrorCodes.BUSINESS_RULE, "Entrega já finalizada (" + status + ").");
        }
    }
}
