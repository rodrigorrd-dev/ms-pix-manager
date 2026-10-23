package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.ScheduledOccurrence;
import br.com.pixmanager.core.domain.entity.ScheduledPayment;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduledPaymentPersistencePortOut {

    /** Agendamento novo + todas as suas ocorrências, na transação corrente. */
    void insert(ScheduledPayment payment, List<ScheduledOccurrence> occurrences);

    Optional<ScheduledPayment> findById(UUID id);

    /** Versão desatualizada ⇒ {@code PreconditionFailedException}. */
    ScheduledPayment save(ScheduledPayment payment);

    PageResult<ScheduledPayment> listByPayer(UUID payerAccountId, PageQuery page);

    List<ScheduledOccurrence> occurrencesOf(UUID scheduledPaymentId);

    Optional<ScheduledOccurrence> findOccurrence(UUID occurrenceId);

    /** Versão desatualizada ⇒ {@code PreconditionFailedException}. */
    ScheduledOccurrence saveOccurrence(ScheduledOccurrence occurrence);

    /** Cancela, num único UPDATE, as ocorrências canceláveis (SCHEDULED/RETRY_PENDING) e devolve as canceladas. */
    List<ScheduledOccurrence> cancelOpenOccurrences(UUID scheduledPaymentId);

    /**
     * Reivindica, ATOMICAMENTE (FOR UPDATE SKIP LOCKED), as ocorrências vencidas de agendamentos ACTIVE: já as devolve em
     * {@code PROCESSING} com a tentativa contada. Duas instâncias nunca recebem a mesma.
     */
    List<ScheduledOccurrence> claimDue(LocalDate today, Instant now, int limit);

    /** PROCESSING com ordem já criada: aguardam o desfecho da ordem. */
    List<ScheduledOccurrence> processingWithOrder(int limit);

    /** PROCESSING SEM ordem há mais que o limite (instância caiu entre reivindicar e criar a ordem). */
    List<ScheduledOccurrence> processingWithoutOrderSince(Instant olderThan, int limit);

    /** Ocorrências ainda não terminadas do agendamento. */
    long countOpen(UUID scheduledPaymentId);
}
