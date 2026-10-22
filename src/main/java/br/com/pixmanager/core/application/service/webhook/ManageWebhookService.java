package br.com.pixmanager.core.application.service.webhook;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.entity.WebhookSubscription;
import br.com.pixmanager.core.port.in.webhook.ManageWebhookUseCase;
import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut;
import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut.Verdict;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookSubscriptionPortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookSubscriptionPortOut.WebhookView;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Cadastro do webhook por chave. A URL é validada contra a política anti-SSRF AQUI (rejeita na hora) e de novo a cada envio (o DNS pode mudar).
 * Chave de outra conta ⇒ 404 (não revela que existe).
 */
public class ManageWebhookService implements ManageWebhookUseCase {

    private final PixKeyPersistencePortOut keys;
    private final WebhookSubscriptionPortOut subscriptions;
    private final WebhookUrlPolicyPortOut urlPolicy;
    private final IdGeneratorPortOut ids;
    private final Clock clock;

    public ManageWebhookService(PixKeyPersistencePortOut keys, WebhookSubscriptionPortOut subscriptions, WebhookUrlPolicyPortOut urlPolicy,
                                IdGeneratorPortOut ids, Clock clock) {
        this.keys = keys;
        this.subscriptions = subscriptions;
        this.urlPolicy = urlPolicy;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public Registered put(Caller caller, String keyValue, String webhookUrl) {
        PixKey key = owned(caller, keyValue);
        if (!key.isActive()) {
            throw new BusinessException(ErrorCodes.KEY_NOT_ACTIVE, "A chave precisa estar ativa para receber webhook (status " + key.status() + ").");
        }
        Verdict verdict = urlPolicy.check(webhookUrl);
        if (!verdict.allowed()) {
            throw new BusinessException(ErrorCodes.WEBHOOK_URL_REJECTED, "URL de webhook não permitida: " + verdict.describe() + ".");
        }
        Optional<WebhookSubscription> existing = subscriptions.findByPixKeyId(key.id());
        WebhookSubscription saved = existing
                .map(subscription -> subscriptions.save(subscription.withUrl(webhookUrl)))
                .orElseGet(() -> subscriptions.save(WebhookSubscription.register(ids.newId(), key.id(), webhookUrl, clock.instant(), caller.clientId())));
        return new Registered(saved, key.value().value(), existing.isEmpty());
    }

    @Override
    public Optional<WebhookView> get(Caller caller, String keyValue) {
        PixKey key = owned(caller, keyValue);
        return subscriptions.findByPixKeyId(key.id()).map(subscription -> new WebhookView(subscription, key.accountId(), key.value().value()));
    }

    @Override
    public boolean delete(Caller caller, String keyValue) {
        PixKey key = owned(caller, keyValue);
        Optional<WebhookSubscription> existing = subscriptions.findByPixKeyId(key.id());
        existing.ifPresent(subscription -> subscriptions.delete(subscription.id()));
        return existing.isPresent();
    }

    @Override
    public PageResult<WebhookView> list(Caller caller, Instant from, Instant to, PageQuery page) {
        return subscriptions.list(caller.accounts(), caller.allAccounts(), from, to, page);
    }

    private PixKey owned(Caller caller, String keyValue) {
        PixKeyValue value = PixKeyValue.infer(keyValue).orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Chave não encontrada."));
        return keys.findLiveByValue(value).filter(pixKey -> caller.canAccess(pixKey.accountId()))
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Chave não encontrada."));
    }
}
