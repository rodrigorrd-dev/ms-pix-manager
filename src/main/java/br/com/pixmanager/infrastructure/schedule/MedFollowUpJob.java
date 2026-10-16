package br.com.pixmanager.infrastructure.schedule;

import br.com.pixmanager.core.port.in.med.FollowMedCasesUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fecha os casos MED cuja devolução foi liquidada (ou devolve à operação os que não se realizaram). ShedLock: um por cluster. */
@Component
public class MedFollowUpJob {

    private final FollowMedCasesUseCase follow;

    MedFollowUpJob(FollowMedCasesUseCase follow) {
        this.follow = follow;
    }

    @Scheduled(fixedDelayString = "${pix.med.follow-delay:PT10S}")
    @SchedulerLock(name = "med-follow-up", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    void run() {
        follow.runCycle();
    }
}
