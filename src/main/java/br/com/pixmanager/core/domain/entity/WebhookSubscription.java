package br.com.pixmanager.core.domain.entity;

import br.com.pixmanager.core.domain.enums.WebhookStatus;

import java.time.Instant;
import java.util.UUID;

/** Assinatura de notificação de Pix recebido, uma por chave Pix (API Pix: {@code PUT /webhook/{chave}}). */
public record WebhookSubscription(UUID id, UUID pixKeyId, String url, WebhookStatus status, long version, Instant createdAt, String createdBy) {

    public static WebhookSubscription register(UUID id, UUID pixKeyId, String url, Instant now, String createdBy) {
        return new WebhookSubscription(id, pixKeyId, url, WebhookStatus.ACTIVE, 0, now, createdBy);
    }

    /** Troca a URL (e reativa, se estava desabilitada). Mantém id e data de criação. */
    public WebhookSubscription withUrl(String newUrl) {
        return new WebhookSubscription(id, pixKeyId, newUrl, WebhookStatus.ACTIVE, version, createdAt, createdBy);
    }

    public boolean isActive() {
        return status == WebhookStatus.ACTIVE;
    }
}
