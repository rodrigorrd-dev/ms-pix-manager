package br.com.pixmanager.core.application.service.pixkey;

import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.port.in.pixkey.RegisterPixKeyUseCase;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.entity.event.PixKeyEvent;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.domain.entity.Caller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.UUID;

/**
 * Regras BR-KEY-01..05 e BR-DOC-04/06: chave CPF/CNPJ = documento do titular (PF só CPF, PJ só CNPJ), limite de chaves
 * POR CONTA (PF 5 / PJ 20), uma chave viva por valor, estado inicial por tipo. Registro LOCAL e atômico com a outbox;
 * o DICT é chamado depois, fora da transação (BR-KEY-05).
 */
public class RegisterPixKeyService implements RegisterPixKeyUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterPixKeyService.class);

    private final PixKeyPersistencePortOut persistence;
    private final FindAccountUseCase accounts;
    private final DomainEventPublisherPortOut events;
    private final IdGeneratorPortOut ids;
    private final Clock clock;
    private final int maxKeysIndividual;
    private final int maxKeysBusiness;

    public RegisterPixKeyService(PixKeyPersistencePortOut persistence, FindAccountUseCase accounts,
                                 DomainEventPublisherPortOut events, IdGeneratorPortOut ids, Clock clock, int maxKeysIndividual, int maxKeysBusiness) {
        this.persistence = persistence;
        this.accounts = accounts;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.maxKeysIndividual = maxKeysIndividual;
        this.maxKeysBusiness = maxKeysBusiness;
    }

    @Override
    public PixKey register(Caller caller, UUID accountId, PixKeyType type, String rawValue) {
        if (!caller.canAccess(accountId)) {
            throw notFound();                                   // fora do escopo ⇒ 404, nunca 403
        }
        AccountInfo account = accounts.findById(accountId).orElseThrow(RegisterPixKeyService::notFound);
        if (!account.isActive()) {
            throw new BusinessException(ErrorCodes.KEY_NOT_ACTIVE, "A conta não está ativa.");
        }
        String effectiveValue = type == PixKeyType.EVP ? ids.newId().toString() : rawValue;   // EVP: ver docs/10 E6
        PixKeyValue value = PixKeyValue.of(type, effectiveValue);
        assertOwnedByHolder(account, value);
        assertWithinLimit(account);
        if (persistence.findLiveByValue(value).isPresent()) {
            throw new ConflictException(ErrorCodes.CONFLICT, "A chave informada já está registrada.");
        }
        PixKey saved = persistence.save(PixKey.register(ids.newId(), accountId, value, clock.instant()));
        events.publish(new PixKeyEvent("PixKeyRegistered", saved));
        log.info("Chave Pix registrada: id={} tipo={} conta={}", saved.id(), saved.value().type(), accountId);
        return saved;
    }

    /** BR-KEY-02 / BR-DOC-06: CPF só em conta PF, CNPJ só em conta PJ, e igual ao documento do titular. */
    private static void assertOwnedByHolder(AccountInfo account, PixKeyValue value) {
        if (!value.type().isDocumentKey()) {
            return;
        }
        boolean typeMatchesHolder = (value.type() == PixKeyType.CPF) == account.isIndividual();
        if (!typeMatchesHolder || !account.document().value().equals(value.value())) {
            throw new BusinessException(ErrorCodes.KEY_NOT_OWNED, "A chave CPF/CNPJ deve ser o documento do titular da conta.");
        }
    }

    private void assertWithinLimit(AccountInfo account) {
        int max = account.isIndividual() ? maxKeysIndividual : maxKeysBusiness;
        if (persistence.countLiveByAccount(account.id()) >= max) {
            throw new BusinessException(ErrorCodes.KEY_LIMIT_EXCEEDED, "Limite de chaves por conta atingido.");
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada.");
    }
}
