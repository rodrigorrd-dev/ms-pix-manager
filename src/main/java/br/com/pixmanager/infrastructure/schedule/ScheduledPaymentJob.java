package br.com.pixmanager.infrastructure.schedule;

import br.com.pixmanager.core.port.in.schedule.ExecuteScheduledPaymentsUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker do Pix Agendado. ShedLock: um por cluster (duas réplicas nunca disparam o mesmo ciclo; o claim SKIP LOCKED é a 2ª barreira). */
@Component
public class ScheduledPaymentJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPaymentJob.class);

    private final ExecuteScheduledPaymentsUseCase execute;

    ScheduledPaymentJob(ExecuteScheduledPaymentsUseCase execute) {
        this.execute = execute;
    }

    @Scheduled(fixedDelayString = "${pix.schedule.dispatch-delay:PT30S}")
    @SchedulerLock(name = "scheduled-payments", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1S")
    void run() {
        var r = execute.runCycle();
        if (r.started() + r.settled() + r.retried() + r.failed() + r.released() > 0) {
            log.info("Pix agendado: disparados={} executados={} retentativas={} falhas={} liberados={}", r.started(), r.settled(), r.retried(), r.failed(), r.released());
        }
    }
}
