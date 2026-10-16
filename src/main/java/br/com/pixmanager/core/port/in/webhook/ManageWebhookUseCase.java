package br.com.pixmanager.core.port.in.webhook;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.WebhookSubscription;
import br.com.pixmanager.core.port.out.persistence.WebhookSubscriptionPortOut.WebhookView;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Instant;
import java.util.Optional;

/** Cadastro do webhook de uma chave (API Pix {@code /webhook}). A chave é referida pelo VALOR e precisa ser do chamador e estar ACTIVE. */
public interface ManageWebhookUseCase {

    Registered put(Caller caller, String keyValue, String webhookUrl);

    Optional<WebhookView> get(Caller caller, String keyValue);

    /** @return {@code true} se havia webhook e foi removido. */
    boolean delete(Caller caller, String keyValue);

    PageResult<WebhookView> list(Caller caller, Instant from, Instant to, PageQuery page);

    record Registered(WebhookSubscription subscription, String keyValue, boolean created) {}
}
