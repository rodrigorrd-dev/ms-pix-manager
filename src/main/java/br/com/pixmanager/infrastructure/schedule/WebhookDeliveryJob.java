package br.com.pixmanager.infrastructure.schedule;

import br.com.pixmanager.core.port.in.webhook.DispatchWebhooksUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Envia as entregas de webhook vencidas. ShedLock: um por cluster (as entregas têm controle de versão, então uma corrida não duplica estado). */
@Component
public class WebhookDeliveryJob {

    private final DispatchWebhooksUseCase dispatch;
    private final int batch;

    WebhookDeliveryJob(DispatchWebhooksUseCase dispatch, @Value("${pix.webhook.dispatch-batch:20}") int batch) {
        this.dispatch = dispatch;
        this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${pix.webhook.dispatch-delay:PT2S}")
    @SchedulerLock(name = "webhook-dispatch", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    void run() {
        dispatch.dispatchDue(batch);
    }
}
