package br.com.pixmanager.core.application.service.pixkey;

import br.com.pixmanager.core.port.in.pixkey.ManagePixKeyUseCase;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.domain.enums.DeletionReason;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.event.PixKeyEvent;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.shared.model.ExpectedVersion;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code delete} chama o DICT (HTTP) FORA de transação e só depois persiste DELETED + outbox atomicamente
 * ({@link TransactionRunnerPortOut}). Crash entre as duas pontas é reparado pela reconciliação com o DICT (CID).
 */
public class ManagePixKeyService implements ManagePixKeyUseCase {

    private final PixKeyPersistencePortOut persistence;
    private final DictGatewayPortOut dict;
    private final DomainEventPublisherPortOut events;
    private final TransactionRunnerPortOut tx;
    private final Clock clock;

    public ManagePixKeyService(PixKeyPersistencePortOut persistence, DictGatewayPortOut dict, DomainEventPublisherPortOut events,
                               TransactionRunnerPortOut tx, Clock clock) {
        this.persistence = persistence;
        this.dict = dict;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public PixKey get(Caller caller, UUID keyId) {
        return owned(caller, keyId);
    }

    @Override
    public PageResult<PixKey> list(Caller caller, UUID accountId, Optional<PixKeyStatus> status, PageQuery page) {
        if (!caller.canAccess(accountId)) {
            return new PageResult<>(java.util.List.of(), page.page(), page.size(), 0);   // sem vazar existência
        }
        return persistence.list(accountId, status, page);
    }

    @Override
    public PixKey delete(Caller caller, UUID keyId, DeletionReason reason, ExpectedVersion expected) {
        PixKey key = owned(caller, keyId);
        if (!expected.matches(key.version())) {
            throw new PreconditionFailedException("A chave foi alterada por outra requisição (versão desatualizada).");
        }
        PixKey deleted = key.deleted(reason, clock.instant());          // valida a transição antes de qualquer I/O
        if (key.dictCid() != null) {
            dict.delete(key, reason);                                   // HTTP via dict-gateway, fora de transação
        }
        return tx.inTransaction(() -> {
            PixKey saved = persistence.save(deleted);
            events.publish(new PixKeyEvent("PixKeyDeleted", saved));
            return saved;
        });
    }

    private PixKey owned(Caller caller, UUID keyId) {
        PixKey key = persistence.findById(keyId)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Chave não encontrada."));
        if (!caller.canAccess(key.accountId())) {
            throw new NotFoundException(ErrorCodes.NOT_FOUND, "Chave não encontrada.");
        }
        return key;
    }
}
