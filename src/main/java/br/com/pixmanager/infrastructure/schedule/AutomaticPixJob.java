package br.com.pixmanager.infrastructure.schedule;

import br.com.pixmanager.core.port.in.automatic.ExecuteAutomaticUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker do Pix Automático. ShedLock: um por cluster; o claim SKIP LOCKED é a segunda barreira contra duplicidade. */
@Component
public class AutomaticPixJob {

    private static final Logger log = LoggerFactory.getLogger(AutomaticPixJob.class);

    private final ExecuteAutomaticUseCase execute;

    AutomaticPixJob(ExecuteAutomaticUseCase execute) {
        this.execute = execute;
    }

    @Scheduled(initialDelayString = "${pix.automatic.dispatch-delay:PT30S}", fixedDelayString = "${pix.automatic.dispatch-delay:PT30S}")   // 1ª execução também só depois do atraso: não corre junto com o início dos ITs
    @SchedulerLock(name = "automatic-pix", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1S")
    void run() {
        var r = execute.runCycle();
        if (r.expired() + r.started() + r.paid() + r.retried() + r.failed() + r.released() > 0) {
            log.info("Pix automático: expiradas={} disparadas={} pagas={} novas tentativas={} falhas={} liberadas={}", r.expired(), r.started(), r.paid(), r.retried(), r.failed(), r.released());
        }
    }
}
