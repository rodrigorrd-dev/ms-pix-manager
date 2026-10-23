package br.com.pixmanager.core.application.service.charge;

import br.com.pixmanager.core.port.in.charge.ChargeDetails;
import br.com.pixmanager.core.port.in.charge.ReviseChargeUseCase;
import br.com.pixmanager.core.port.out.persistence.ChargePersistencePortOut;
import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.core.domain.entity.event.ChargeEvent;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.shared.model.ExpectedVersion;

import java.time.Clock;

public class ReviseChargeService implements ReviseChargeUseCase {

    private final FindChargeService finder;
    private final ChargePersistencePortOut persistence;
    private final DomainEventPublisherPortOut events;
    private final Clock clock;

    public ReviseChargeService(FindChargeService finder, ChargePersistencePortOut persistence,
                               DomainEventPublisherPortOut events, Clock clock) {
        this.finder = finder;
        this.persistence = persistence;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public ChargeDetails revise(Caller caller, TxId txid, ExpectedVersion expected, ReviseChargeCommand command) {
        Charge charge = finder.visible(caller, txid)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Cobrança não encontrada."));
        if (!expected.matches(charge.version())) {
            throw new PreconditionFailedException("A cobrança foi alterada por outra requisição (versão desatualizada).");
        }
        Charge changed = command.remove()
                ? charge.removedByReceiver(clock.instant())
                : charge.revised(command.amount(), command.debtor(), command.payerRequest(), command.expirationSeconds(), clock.instant());
        Charge saved = persistence.save(changed);
        events.publish(new ChargeEvent(command.remove() ? "ChargeRemoved" : "ChargeRevised", saved));
        return finder.details(saved);
    }
}
