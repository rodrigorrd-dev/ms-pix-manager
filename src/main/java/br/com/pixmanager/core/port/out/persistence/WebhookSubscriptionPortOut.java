package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.WebhookSubscription;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface WebhookSubscriptionPortOut {

    /** Insere ou atualiza a URL/estado. */
    WebhookSubscription save(WebhookSubscription subscription);

    Optional<WebhookSubscription> findByPixKeyId(UUID pixKeyId);

    void delete(UUID subscriptionId);

    /** Assinaturas das contas informadas (ou de todas, se {@code allAccounts}), criadas no intervalo. O valor da chave vem decifrado. */
    PageResult<WebhookView> list(Set<UUID> accountIds, boolean allAccounts, Instant from, Instant to, PageQuery page);

    record WebhookView(WebhookSubscription subscription, UUID accountId, String keyValue) {}
}
