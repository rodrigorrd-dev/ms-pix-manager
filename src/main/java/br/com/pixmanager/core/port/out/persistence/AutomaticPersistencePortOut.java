package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.AutomaticAttempt;
import br.com.pixmanager.core.domain.entity.AutomaticAuthorization;
import br.com.pixmanager.core.domain.entity.AutomaticCharge;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AutomaticPersistencePortOut {

    /** idRec duplicado ⇒ {@code ConflictException}. */
    void insertAuthorization(AutomaticAuthorization authorization);

    Optional<AutomaticAuthorization> findAuthorization(UUID id);

    Optional<AutomaticAuthorization> findAuthorizationByIdRec(String idRec);

    /** Versão desatualizada ⇒ {@code PreconditionFailedException}. */
    AutomaticAuthorization saveAuthorization(AutomaticAuthorization authorization);

    PageResult<AutomaticAuthorization> listAuthorizations(Set<UUID> accounts, boolean allAccounts, String status, PageQuery page);

    /** Solicitações pendentes cujo prazo de decisão venceu. */
    List<AutomaticAuthorization> findExpiredRequests(Instant now, int limit);

    /** txid repetido na mesma autorização ⇒ {@code ConflictException}. */
    void insertCharge(AutomaticCharge charge, AutomaticAttempt firstAttempt);

    Optional<AutomaticCharge> findCharge(UUID id);

    AutomaticCharge saveCharge(AutomaticCharge charge);

    List<AutomaticCharge> chargesOf(UUID authorizationId);

    List<AutomaticAttempt> attemptsOf(UUID chargeId);

    void insertAttempt(AutomaticAttempt attempt);

    /** Cancela, em lote, as cobranças ATIVA da autorização e suas tentativas AGENDADA (sem controle de versão: é o cancelamento da autorização inteira). */
    void cancelLiveCharges(UUID authorizationId);

    AutomaticAttempt saveAttempt(AutomaticAttempt attempt);

    /**
     * Reivindica ATOMICAMENTE (SKIP LOCKED) tentativas AGENDADA vencidas de cobranças ATIVAS: já as devolve SOLICITADA. Só pega as que não
     * mudaram desde {@code notUpdatedSince} (falha técnica recém-ocorrida espera, em vez de martelar a dependência).
     */
    List<AutomaticAttempt> claimDue(LocalDate today, Instant notUpdatedSince, int limit);

    /** SOLICITADA com ordem criada: aguardam o desfecho da ordem. */
    List<AutomaticAttempt> inProgressWithOrder(int limit);

    /** SOLICITADA SEM ordem há mais que o limite: a instância caiu entre reivindicar e criar a ordem. */
    List<AutomaticAttempt> inProgressWithoutOrderSince(Instant olderThan, int limit);
}
