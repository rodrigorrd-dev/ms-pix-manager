package br.com.pixmanager.core.application.service.webhook;

import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.WebhookDelivery;
import br.com.pixmanager.core.domain.entity.WebhookSubscription;
import br.com.pixmanager.core.port.in.webhook.NotifyPixReceivedUseCase;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookDeliveryPortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookSubscriptionPortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookTargetPortOut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Transforma um Pix recebido numa entrega pendente para o webhook da chave. Corpo no formato da API Pix: {@code {"pix":[{endToEndId, txid,
 * chave, valor, horario, infoPagador}]}} (sem dados do pagador). A entrega é única por (assinatura, Pix): reprocessar a mensagem não duplica.
 */
public class WebhookNotificationService implements NotifyPixReceivedUseCase {

    public static final String EVENT_TYPE = "pix.received";
    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationService.class);

    private final WebhookTargetPortOut targets;
    private final PixKeyPersistencePortOut keys;
    private final WebhookSubscriptionPortOut subscriptions;
    private final WebhookDeliveryPortOut deliveries;
    private final IdGeneratorPortOut ids;
    private final Clock clock;

    public WebhookNotificationService(WebhookTargetPortOut targets, PixKeyPersistencePortOut keys, WebhookSubscriptionPortOut subscriptions,
                                      WebhookDeliveryPortOut deliveries, IdGeneratorPortOut ids, Clock clock) {
        this.targets = targets;
        this.keys = keys;
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public void pixReceived(ReceivedPix pix, String receiverKeyHash) {
        try {
            Optional<UUID> keyId = targets.keyIdFor(receiverKeyHash, pix.chargeId());
            if (keyId.isEmpty()) {
                return;
            }
            Optional<WebhookSubscription> subscription = subscriptions.findByPixKeyId(keyId.get()).filter(WebhookSubscription::isActive);
            Optional<PixKey> key = keys.findById(keyId.get());
            if (subscription.isEmpty() || key.isEmpty()) {
                return;
            }
            deliveries.enqueue(WebhookDelivery.pending(ids.newId(), subscription.get().id(), pix.id(), EVENT_TYPE, clock.instant()),
                    payload(pix, key.get().value().value()));
        } catch (RuntimeException exception) {
            // notificar é secundário: um erro aqui jamais pode impedir o crédito do Pix (que já foi liquidado no SPI)
            log.error("Falha ao enfileirar webhook do Pix {}: {}", pix.endToEndId(), exception.toString());
        }
    }

    static Map<String, Object> payload(ReceivedPix pix, String keyValue) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("endToEndId", pix.endToEndId().value());
        if (pix.txid() != null) {
            item.put("txid", pix.txid());
        }
        item.put("chave", keyValue);
        item.put("valor", pix.amount().toBcb());
        item.put("horario", pix.settledAt().toString());
        if (pix.payerInfo() != null) {
            item.put("infoPagador", pix.payerInfo());
        }
        return Map.of("pix", List.of(item));
    }
}
