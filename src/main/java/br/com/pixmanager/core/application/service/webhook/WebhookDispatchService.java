package br.com.pixmanager.core.application.service.webhook;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.WebhookDelivery;
import br.com.pixmanager.core.domain.entity.WebhookRetryPolicy;
import br.com.pixmanager.core.port.in.webhook.DispatchWebhooksUseCase;
import br.com.pixmanager.core.port.out.WebhookSenderPortOut;
import br.com.pixmanager.core.port.out.WebhookSenderPortOut.SendResult;
import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut;
import br.com.pixmanager.core.port.out.WebhookUrlPolicyPortOut.Verdict;
import br.com.pixmanager.core.port.out.persistence.WebhookDeliveryPortOut;
import br.com.pixmanager.core.port.out.persistence.WebhookDeliveryPortOut.DueDelivery;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.exception.ForbiddenException;
import br.com.pixmanager.shared.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Envia as entregas vencidas. 2xx ⇒ DELIVERED; qualquer outra coisa (4xx, 5xx, timeout, DNS, URL que passou a ser bloqueada) ⇒ nova tentativa
 * com backoff e jitter até esgotar ⇒ DEAD (reenvio manual). A URL é re-validada A CADA envio (anti-SSRF: o DNS pode apontar para dentro depois).
 * Cada envio é sequencial (um receptor lento atrasa os outros do lote: o tamanho do lote e o timeout limitam isso).
 */
public class WebhookDispatchService implements DispatchWebhooksUseCase {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchService.class);

    private final WebhookDeliveryPortOut deliveries;
    private final WebhookSenderPortOut sender;
    private final WebhookUrlPolicyPortOut urlPolicy;
    private final WebhookRetryPolicy retryPolicy;
    private final Clock clock;
    private final DoubleSupplier random;

    public WebhookDispatchService(WebhookDeliveryPortOut deliveries, WebhookSenderPortOut sender, WebhookUrlPolicyPortOut urlPolicy,
                                  WebhookRetryPolicy retryPolicy, Clock clock, DoubleSupplier random) {
        this.deliveries = deliveries;
        this.sender = sender;
        this.urlPolicy = urlPolicy;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.random = random;
    }

    @Override
    public int dispatchDue(int batch) {
        List<DueDelivery> due = deliveries.findDue(clock.instant(), batch);
        for (DueDelivery dueDelivery : due) {
            Verdict verdict = urlPolicy.check(dueDelivery.url());
            SendResult result = verdict.allowed() ? sender.send(dueDelivery.url(), dueDelivery.payloadJson())
                    : new SendResult(null, "URL bloqueada pela política: " + verdict.describe());
            WebhookDelivery next = result.success()
                    ? dueDelivery.delivery().delivered(result.httpStatus(), clock.instant())
                    : dueDelivery.delivery().failed(result.httpStatus(), result.error() != null ? result.error() : "HTTP " + result.httpStatus(),
                            clock.instant(), retryPolicy, random.getAsDouble());
            try {
                deliveries.save(next);
            } catch (ConflictException exception) {
                log.debug("Entrega {} foi alterada por outra instância; segue sem sobrescrever", dueDelivery.delivery().id());
            }
        }
        return due.size();
    }

    @Override
    public WebhookDelivery resend(Caller caller, UUID deliveryId) {
        if (!caller.allAccounts()) {
            throw new ForbiddenException(ErrorCodes.ACCESS_DENIED, "Reenvio de entrega é restrito ao back-office.");
        }
        WebhookDelivery delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Entrega não encontrada."));
        return deliveries.save(delivery.requeued(clock.instant()));
    }
}
