package br.com.pixmanager.infrastructure.schedule;

import br.com.pixmanager.core.port.in.pixkey.ActivatePendingKeysUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ativa no DICT as chaves PENDING_DICT (fora da transação do registro). ShedLock: um por cluster. */
@Component
public class PendingDictKeysJob {

    private final ActivatePendingKeysUseCase activate;

    PendingDictKeysJob(ActivatePendingKeysUseCase activate) {
        this.activate = activate;
    }

    @Scheduled(fixedDelayString = "${pix.dict-gateway.sweeper-delay:PT3S}")
    @SchedulerLock(name = "pixkey-activate-pending", lockAtMostFor = "PT2M", lockAtLeastFor = "PT1S")
    void run() {
        activate.activatePending(50);
    }
}
