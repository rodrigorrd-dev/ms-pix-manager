package br.com.pixmanager.core.application.service.webhook;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.entity.PixPayer;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.WebhookDelivery;
import br.com.pixmanager.core.domain.entity.WebhookRetryPolicy;
import br.com.pixmanager.core.domain.entity.WebhookSubscription;
import br.com.pixmanager.core.domain.enums.DeliveryStatus;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.port.in.webhook.ManageWebhookUseCase.Registered;
import br.com.pixmanager.core.port.out.WebhookSenderPortOut;
import br.com.pixmanager.core.port.out.WebhookSenderPortOut.SendResult;
import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut;
import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut.Verdict;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookDeliveryPortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookSubscriptionPortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookTargetPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.ForbiddenException;
import br.com.pixmanager.shared.exception.InvalidTransitionException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Webhook: cadastro por chave, notificação do Pix recebido e entrega com retry")
class WebhookServicesTest {

    static final UUID ACCOUNT = SequentialIds.id(900);
    static final String EMAIL = "loja@example.com";

    // ---- dublês em memória
    final Map<UUID, PixKey> keys = new HashMap<>();
    final Map<UUID, WebhookSubscription> subs = new HashMap<>();
    final Map<UUID, WebhookDelivery> deliveries = new HashMap<>();
    final Map<UUID, String> payloads = new HashMap<>();
    final List<String> sentTo = new ArrayList<>();
    boolean urlAllowed = true;
    Verdict nextVerdict;
    SendResult nextSend = new SendResult(200, null);
    boolean saveConflicts;
    UUID targetKey;

    FixedClock clock;
    PixKey key;
    final Caller owner = Caller.forAccounts("cli", ACCOUNT);

    final PixKeyPersistencePortOut keyPort = new PixKeyPersistencePortOut() {
        public PixKey save(PixKey pixKey) { keys.put(pixKey.id(), pixKey); return pixKey; }
        public Optional<PixKey> findById(UUID id) { return Optional.ofNullable(keys.get(id)); }
        public Optional<PixKey> findLiveByValue(PixKeyValue pixKeyValue) { return keys.values().stream().filter(pixKey -> pixKey.value().equals(pixKeyValue)).findFirst(); }
        public long countLiveByAccount(UUID accountId) { return 0; }
        public PageResult<PixKey> list(UUID accountId, Optional<PixKeyStatus> pixKeyStatus, PageQuery pageQuery) { return new PageResult<>(List.of(), 0, 20, 0); }
        public List<PixKey> findPendingDict(Instant olderThan, int limit) { return List.of(); }
    };
    final WebhookSubscriptionPortOut subPort = new WebhookSubscriptionPortOut() {
        public WebhookSubscription save(WebhookSubscription webhookSubscription) { subs.put(webhookSubscription.id(), webhookSubscription); return webhookSubscription; }
        public Optional<WebhookSubscription> findByPixKeyId(UUID pixKeyId) { return subs.values().stream().filter(subscription -> subscription.pixKeyId().equals(pixKeyId)).findFirst(); }
        public void delete(UUID id) { subs.remove(id); }
        public PageResult<WebhookView> list(Set<UUID> accounts, boolean all, Instant from, Instant to, PageQuery page) {
            List<WebhookView> webhookViews = subs.values().stream().map(subscription -> new WebhookView(subscription, ACCOUNT, EMAIL)).filter(view -> all || accounts.contains(view.accountId())).toList();
            return new PageResult<>(webhookViews, page.page(), page.size(), webhookViews.size());
        }
    };
    final WebhookDeliveryPortOut deliveryPort = new WebhookDeliveryPortOut() {
        public void enqueue(WebhookDelivery delivery, Map<String, Object> payload) {
            boolean duplicate = deliveries.values().stream().anyMatch(existing -> existing.subscriptionId().equals(delivery.subscriptionId()) && existing.sourceEventId().equals(delivery.sourceEventId()));
            if (!duplicate) { deliveries.put(delivery.id(), delivery); payloads.put(delivery.id(), payload.toString()); }
        }
        public List<DueDelivery> findDue(Instant now, int limit) {
            return deliveries.values().stream().filter(delivery -> delivery.isDue(now)).limit(limit)
                    .map(delivery -> new DueDelivery(delivery, subs.get(delivery.subscriptionId()).url(), payloads.get(delivery.id()))).toList();
        }
        public Optional<WebhookDelivery> findById(UUID id) { return Optional.ofNullable(deliveries.get(id)); }
        public WebhookDelivery save(WebhookDelivery delivery) {
            if (saveConflicts) throw new ConflictException(ErrorCodes.CONFLICT, "versão");
            deliveries.put(delivery.id(), delivery); return delivery;
        }
    };
    final WebhookUrlPolicyPortOut policy = url -> nextVerdict != null ? nextVerdict : (urlAllowed ? new Verdict.Allowed() : new Verdict.Forbidden("só https é aceito"));
    final WebhookSenderPortOut sender = (url, body) -> { sentTo.add(url); return nextSend; };
    final WebhookTargetPortOut target = (hash, charge) -> Optional.ofNullable(targetKey);

    ManageWebhookService manage;
    WebhookNotificationService notify;
    WebhookDispatchService dispatch;

    @BeforeEach
    void setUp() {
        clock = FixedClock.at("2026-10-16T12:00:00Z");
        SequentialIds ids = new SequentialIds();
        manage = new ManageWebhookService(keyPort, subPort, policy, ids, clock);
        notify = new WebhookNotificationService(target, keyPort, subPort, deliveryPort, ids, clock);
        dispatch = new WebhookDispatchService(deliveryPort, sender, policy, new WebhookRetryPolicy(3, Duration.ofSeconds(5), Duration.ofHours(1), 0), clock, () -> 0.5);
        key = keyPort.save(new PixKey(SequentialIds.id(1), ACCOUNT, PixKeyValue.of(PixKeyType.EMAIL, EMAIL), PixKeyStatus.ACTIVE, "cid", clock.instant(), null, null, 0, clock.instant()));
        targetKey = key.id();
    }

    ReceivedPix pix(String payerInfo) {
        return ReceivedPix.receive(SequentialIds.id(77), new EndToEndId("E12345678202610161200abcdefghijk"), "txidtxidtxidtxidtxidtxidtxid12", ACCOUNT, Money.of("10.00"),
                new PixPayer(null, "Fulano", "60746948", "0001", "1", "CACC"), payerInfo, Instant.parse("2026-10-16T11:59:00Z"), "msg-1", null, clock.instant());
    }

    // ------------------------------------------------------------------ cadastro
    @Test
    @DisplayName("PUT: cria na 1ª vez (created=true) e só troca a URL nas seguintes (created=false, mesmo id)")
    void putCreatesThenUpdates() {
        Registered first = manage.put(owner, EMAIL, "https://loja.example/hook");
        Registered second = manage.put(owner, EMAIL, "https://loja.example/novo");

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.subscription().id()).isEqualTo(first.subscription().id());
        assertThat(subs).hasSize(1);
        assertThat(subs.values().iterator().next().url()).isEqualTo("https://loja.example/novo");
        assertThat(first.keyValue()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("URL fora da política (SSRF) ⇒ 422 PIX-422-017 com o motivo; nada é gravado")
    void putRejectsForbiddenUrl() {
        urlAllowed = false;
        assertThatThrownBy(() -> manage.put(owner, EMAIL, "http://x")).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(ErrorCodes.WEBHOOK_URL_REJECTED);
            assertThat(exception.getMessage()).contains("só https");
        });
        nextVerdict = new Verdict.Unresolvable("x.invalid");
        assertThatThrownBy(() -> manage.put(owner, EMAIL, "https://x.invalid")).hasMessageContaining("não resolve");
        assertThat(subs).isEmpty();
    }

    @Test
    @DisplayName("chave que não é do chamador, inexistente ou ilegível ⇒ 404 (sem revelar existência); chave não ativa ⇒ 422 PIX-422-021")
    void ownershipAndState() {
        Caller other = Caller.forAccounts("outro", SequentialIds.id(777));
        assertThatThrownBy(() -> manage.put(other, EMAIL, "https://a.example/h")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> manage.get(other, EMAIL)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> manage.delete(other, EMAIL)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> manage.put(owner, "desconhecida@example.com", "https://a.example/h")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> manage.put(owner, "???", "https://a.example/h")).isInstanceOf(NotFoundException.class);

        keyPort.save(new PixKey(key.id(), ACCOUNT, key.value(), PixKeyStatus.PENDING_DICT, null, null, null, null, 0, clock.instant()));
        assertThatThrownBy(() -> manage.put(owner, EMAIL, "https://a.example/h")).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(ErrorCodes.KEY_NOT_ACTIVE));
    }

    @Test
    @DisplayName("GET / DELETE / LIST: consulta, remove (uma vez) e lista só as contas do chamador")
    void getDeleteList() {
        assertThat(manage.get(owner, EMAIL)).isEmpty();
        manage.put(owner, EMAIL, "https://loja.example/hook");

        assertThat(manage.get(owner, EMAIL)).hasValueSatisfying(view -> assertThat(view.subscription().url()).isEqualTo("https://loja.example/hook"));
        assertThat(manage.list(owner, Instant.EPOCH, clock.instant(), PageQuery.of(0, 20)).content()).hasSize(1);
        assertThat(manage.list(Caller.forAccounts("outro", SequentialIds.id(777)), Instant.EPOCH, clock.instant(), PageQuery.of(0, 20)).content()).isEmpty();
        assertThat(manage.list(Caller.system("backoffice"), Instant.EPOCH, clock.instant(), PageQuery.of(0, 20)).content()).hasSize(1);
        assertThat(manage.delete(owner, EMAIL)).isTrue();
        assertThat(manage.delete(owner, EMAIL)).isFalse();
        assertThat(subs).isEmpty();
    }

    // ------------------------------------------------------------------ notificação
    @Test
    @DisplayName("Pix recebido na chave com webhook ⇒ entrega PENDING com o corpo no formato da API Pix (sem dados do pagador); reprocessar não duplica")
    void enqueuesTheBcbPayloadOnce() {
        manage.put(owner, EMAIL, "https://loja.example/hook");

        notify.pixReceived(pix("pedido 42"), "hash-da-chave");
        notify.pixReceived(pix("pedido 42"), "hash-da-chave");          // mesma mensagem reprocessada

        assertThat(deliveries).hasSize(1);
        WebhookDelivery delivery = deliveries.values().iterator().next();
        assertThat(delivery.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.eventType()).isEqualTo("pix.received");
        String body = payloads.get(delivery.id());
        assertThat(body).contains("endToEndId=E12345678202610161200abcdefghijk", "txid=txidtxidtxidtxidtxidtxidtxid12", "chave=" + EMAIL,
                "valor=10.00", "horario=2026-10-16T11:59:00Z", "infoPagador=pedido 42");
        assertThat(body).doesNotContain("Fulano").doesNotContain("60746948");     // nome/banco do pagador não vão no webhook
    }

    @Test
    @DisplayName("sem chave identificável, sem assinatura ou com assinatura DESABILITADA ⇒ nada é enfileirado; erro interno não derruba o crédito")
    void doesNothingWhenThereIsNoTarget() {
        manage.put(owner, EMAIL, "https://loja.example/hook");

        targetKey = null;
        notify.pixReceived(pix(null), null);
        targetKey = key.id();
        WebhookSubscription active = subs.values().iterator().next();
        subs.put(active.id(), new WebhookSubscription(active.id(), active.pixKeyId(), active.url(), br.com.pixmanager.core.domain.enums.WebhookStatus.DISABLED,
                active.version(), active.createdAt(), active.createdBy()));
        notify.pixReceived(pix(null), "h");
        assertThat(deliveries).isEmpty();

        WebhookNotificationService broken = new WebhookNotificationService((delivery, cause) -> { throw new IllegalStateException("banco"); }, keyPort, subPort,
                deliveryPort, new SequentialIds(), clock);
        broken.pixReceived(pix(null), "h");                              // não lança
        assertThat(deliveries).isEmpty();
    }

    // ------------------------------------------------------------------ entrega
    private WebhookDelivery queued() {
        manage.put(owner, EMAIL, "https://loja.example/hook");
        notify.pixReceived(pix(null), "h");
        return deliveries.values().iterator().next();
    }

    @Test
    @DisplayName("2xx ⇒ DELIVERED (uma tentativa); o POST vai para a URL ATUAL da assinatura")
    void deliversOnSuccess() {
        WebhookDelivery delivery = queued();
        manage.put(owner, EMAIL, "https://loja.example/outra");           // a URL mudou depois de enfileirar

        int attempted = dispatch.dispatchDue(10);

        assertThat(attempted).isEqualTo(1);
        assertThat(sentTo).containsExactly("https://loja.example/outra");
        assertThat(deliveries.get(delivery.id()).status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(deliveries.get(delivery.id()).attempts()).isEqualTo(1);
        assertThat(dispatch.dispatchDue(10)).isZero();                    // entregue não volta à fila
    }

    @Test
    @DisplayName("falha ⇒ RETRYING com backoff (5 s, depois 30 s, depois DEAD no limite); a entrega só volta à fila quando vence")
    void retriesWithBackoffThenDies() {
        WebhookDelivery delivery = queued();
        nextSend = new SendResult(503, null);

        dispatch.dispatchDue(10);
        WebhookDelivery first = deliveries.get(delivery.id());
        assertThat(first.status()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(first.lastHttpStatus()).isEqualTo(503);
        assertThat(first.nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(5));
        assertThat(dispatch.dispatchDue(10)).isZero();                    // ainda não venceu

        clock.advance(Duration.ofSeconds(5));
        dispatch.dispatchDue(10);
        assertThat(deliveries.get(delivery.id()).nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(30));

        clock.advance(Duration.ofSeconds(30));
        dispatch.dispatchDue(10);
        assertThat(deliveries.get(delivery.id()).status()).isEqualTo(DeliveryStatus.DEAD);       // 3ª falha esgota (política de teste: 3)
        assertThat(deliveries.get(delivery.id()).attempts()).isEqualTo(3);
        clock.advance(Duration.ofDays(1));
        assertThat(dispatch.dispatchDue(10)).isZero();
    }

    @Test
    @DisplayName("4xx, redirecionamento e erro de rede também contam como falha (só 2xx é sucesso)")
    void onlyTwoXxIsSuccess() {
        for (SendResult result : List.of(new SendResult(400, null), new SendResult(302, null), new SendResult(null, "ConnectException: recusada"))) {
            deliveries.clear();
            subs.clear();
            nextSend = result;
            WebhookDelivery delivery = queued();
            dispatch.dispatchDue(10);
            assertThat(deliveries.get(delivery.id()).status()).as(String.valueOf(result)).isEqualTo(DeliveryStatus.RETRYING);
        }
    }

    @Test
    @DisplayName("URL que passou a ser proibida (DNS apontou para dentro): NÃO envia, registra o motivo e tenta de novo depois")
    void blockedUrlIsNeverCalled() {
        WebhookDelivery delivery = queued();
        nextVerdict = new Verdict.Forbidden("o host resolve para um endereço interno (10.0.0.9)");

        dispatch.dispatchDue(10);

        assertThat(sentTo).isEmpty();
        WebhookDelivery after = deliveries.get(delivery.id());
        assertThat(after.status()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(after.lastError()).contains("bloqueada pela política").contains("10.0.0.9");
    }

    @Test
    @DisplayName("conflito de versão (outra instância atualizou) não derruba o lote")
    void versionConflictIsTolerated() {
        queued();
        saveConflicts = true;
        assertThat(dispatch.dispatchDue(10)).isEqualTo(1);
    }

    @Test
    @DisplayName("reenvio manual: só back-office, só entrega DEAD (zera as tentativas); outras ⇒ 422; inexistente ⇒ 404")
    void manualResend() {
        WebhookDelivery delivery = queued();
        Caller backoffice = Caller.system("backoffice");
        assertThatThrownBy(() -> dispatch.resend(owner, delivery.id())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> dispatch.resend(backoffice, delivery.id())).isInstanceOf(InvalidTransitionException.class);      // ainda PENDING
        assertThatThrownBy(() -> dispatch.resend(backoffice, SequentialIds.id(404))).isInstanceOf(NotFoundException.class);

        nextSend = new SendResult(500, null);
        for (int index = 0; index < 3; index++) { dispatch.dispatchDue(10); clock.advance(Duration.ofHours(2)); }
        assertThat(deliveries.get(delivery.id()).status()).isEqualTo(DeliveryStatus.DEAD);

        WebhookDelivery again = dispatch.resend(backoffice, delivery.id());
        assertThat(again.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(again.attempts()).isZero();
        nextSend = new SendResult(200, null);
        dispatch.dispatchDue(10);
        assertThat(deliveries.get(delivery.id()).status()).isEqualTo(DeliveryStatus.DELIVERED);
    }
}
