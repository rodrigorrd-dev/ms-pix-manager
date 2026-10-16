package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.WebhookDelivery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryPortOut {

    /** Grava a entrega PENDING com o corpo a enviar. Idempotente por (assinatura, evento de origem): repetir não duplica. */
    void enqueue(WebhookDelivery delivery, Map<String, Object> payload);

    /** Entregas PENDING/RETRYING vencidas, mais antigas primeiro, com a URL ATUAL da assinatura e o corpo em JSON. */
    List<DueDelivery> findDue(Instant now, int limit);

    Optional<WebhookDelivery> findById(UUID id);

    /** Atualiza com controle otimista de versão; devolve a entrega com a versão nova. */
    WebhookDelivery save(WebhookDelivery delivery);

    record DueDelivery(WebhookDelivery delivery, String url, String payloadJson) {}
}
