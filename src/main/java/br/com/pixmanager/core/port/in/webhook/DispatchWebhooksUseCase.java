package br.com.pixmanager.core.port.in.webhook;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.WebhookDelivery;

import java.util.UUID;

public interface DispatchWebhooksUseCase {

    /** Envia as entregas vencidas (no máximo {@code batch}). @return quantas foram tentadas. */
    int dispatchDue(int batch);

    /** Reenvio manual de uma entrega DEAD (só back-office: {@code caller.allAccounts()}). */
    WebhookDelivery resend(Caller caller, UUID deliveryId);
}
