package br.com.pixmanager.core.application.service.charge;

import br.com.pixmanager.core.port.in.charge.ChargeDetails;
import br.com.pixmanager.core.port.in.charge.CreateChargeUseCase;
import br.com.pixmanager.core.port.out.persistence.ChargePersistencePortOut;
import br.com.pixmanager.core.port.out.directory.KeyDirectoryPortOut;
import br.com.pixmanager.core.port.out.directory.KeyDirectoryPortOut.ActiveKey;
import br.com.pixmanager.core.port.out.persistence.LocationPortOut;
import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.core.domain.entity.event.ChargeEvent;
import br.com.pixmanager.core.domain.enums.ChargeType;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.domain.entity.Caller;

import java.time.Clock;
import java.util.Optional;

/**
 * BR-COB-01..06: chave ATIVA do próprio recebedor; {@code txid} único por documento do recebedor (mesmo pedido ⇒ devolve o
 * existente; pedido diferente ⇒ conflito); expiração padrão 86400 s; evento {@code ChargeCreated} na MESMA transação.
 */
public class CreateChargeService implements CreateChargeUseCase {

    private final ChargePersistencePortOut persistence;
    private final KeyDirectoryPortOut keys;
    private final LocationPortOut locations;
    private final DomainEventPublisherPortOut events;
    private final IdGeneratorPortOut ids;
    private final Clock clock;
    private final int defaultExpirationSeconds;

    public CreateChargeService(ChargePersistencePortOut persistence, KeyDirectoryPortOut keys, LocationPortOut locations,
                               DomainEventPublisherPortOut events, IdGeneratorPortOut ids, Clock clock, int defaultExpirationSeconds) {
        this.persistence = persistence;
        this.keys = keys;
        this.locations = locations;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.defaultExpirationSeconds = defaultExpirationSeconds;
    }

    @Override
    public CreateChargeResult create(Caller caller, CreateChargeCommand command) {
        ActiveKey key = keys.findActiveKey(command.pixKey())
                .filter(pixKey -> caller.canAccess(pixKey.accountId()))      // chave de outro recebedor é indistinguível de inexistente
                .orElseThrow(() -> new BusinessException(ErrorCodes.KEY_NOT_ACTIVE, "A chave informada não está ativa para este recebedor."));
        TxId txid = new TxId(command.txid() != null ? command.txid() : ids.newId().toString().replace("-", ""));

        Charge candidate = build(command, key, txid);
        Optional<Charge> existing = persistence.findByReceiverHashAndTxid(key.holderDocumentHash(), txid);
        if (existing.isPresent()) {
            return existing.get().sameRequestAs(candidate)
                    ? new CreateChargeResult.AlreadyExists(details(existing.get(), command.pixKey()))
                    : new CreateChargeResult.TxIdConflict();
        }
        Charge saved = persistence.save(candidate.withLocation(locations.create(command.type())));
        events.publish(new ChargeEvent("ChargeCreated", saved));
        return new CreateChargeResult.Created(details(saved, command.pixKey()));
    }

    private Charge build(CreateChargeCommand command, ActiveKey key, TxId txid) {
        var now = clock.instant();
        return command.type() == ChargeType.IMMEDIATE
                ? Charge.createImmediate(ids.newId(), txid, key.accountId(), key.holderDocumentHash(), key.keyId(), command.amount(),
                        command.expirationSeconds() != null ? command.expirationSeconds() : defaultExpirationSeconds,
                        command.debtor(), command.payerRequest(), command.additionalInfo(), null, now)
                : Charge.createDue(ids.newId(), txid, key.accountId(), key.holderDocumentHash(), key.keyId(), command.amount(),
                        command.dueDate(), command.validityAfterDueDays() != null ? command.validityAfterDueDays() : 30, command.debtor(),
                        command.payerRequest(), command.additionalInfo(), null, now);
    }

    private static ChargeDetails details(Charge charge, String rawKey) {
        return new ChargeDetails(charge, rawKey);
    }
}
