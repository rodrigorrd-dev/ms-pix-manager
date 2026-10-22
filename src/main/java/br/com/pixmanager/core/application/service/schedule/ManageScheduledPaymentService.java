package br.com.pixmanager.core.application.service.schedule;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.entity.ScheduledOccurrence;
import br.com.pixmanager.core.domain.entity.ScheduledPayment;
import br.com.pixmanager.core.domain.entity.event.ScheduledPaymentEvent;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.schedule.ManageScheduledPaymentUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.ScheduledPaymentPersistencePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.PreconditionFailedException;
import br.com.pixmanager.shared.model.ExpectedVersion;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.shared.properties.ScheduleProperties;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** BR-SCH-01, BR-SCH-04, BR-SCH-06. Roda dentro de transação (TransactionalUseCases): agendamento + ocorrências + evento atômicos. */
public class ManageScheduledPaymentService implements ManageScheduledPaymentUseCase {

    private final ScheduledPaymentPersistencePortOut persistence;
    private final FindAccountUseCase accounts;
    private final DomainEventPublisherPortOut events;
    private final IdGeneratorPortOut ids;
    private final Clock clock;
    private final ScheduleProperties properties;

    public ManageScheduledPaymentService(ScheduledPaymentPersistencePortOut persistence, FindAccountUseCase accounts,
                                         DomainEventPublisherPortOut events, IdGeneratorPortOut ids, Clock clock,
                                         ScheduleProperties properties) {
        this.persistence = persistence;
        this.accounts = accounts;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public ScheduledPayment create(Caller caller, CreateScheduledPaymentCommand command) {
        if (!caller.canAccess(command.payerAccountId())) {
            throw new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada.");
        }
        AccountInfo payer = accounts.findById(command.payerAccountId()).orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada."));
        if (!payer.isActive()) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "A conta do pagador não está ativa.");
        }
        String key = command.pixKey();
        if (command.method() == PaymentMethod.KEY) {
            key = PixKeyValue.infer(command.pixKey()).filter(inferred -> inferred.type() == command.pixKeyType()).map(PixKeyValue::value)
                    .orElseThrow(() -> new BusinessException(ErrorCodes.INVALID_SCHEDULE, "A chave informada não é válida para o tipo " + command.pixKeyType() + "."));
        }
        LocalDate today = today();
        ScheduledPayment payment = ScheduledPayment.create(ids.newId(), payer.id(), command.recurrence(), command.method(), command.pixKeyType(), key, command.ispb(),
                command.branch(), command.account(), command.accountType(), command.name(), command.document(), command.amount(), command.payerMessage(), command.firstDate(), command.endDate(),
                command.occurrencesTotal(), today, true);
        List<LocalDate> dates = payment.occurrenceDates();
        if (dates.get(dates.size() - 1).isAfter(today.plusDays(properties.maxHorizonDays()))) {
            throw new BusinessException(ErrorCodes.INVALID_SCHEDULE,
                    "A última ocorrência passa do horizonte máximo de " + properties.maxHorizonDays() + " dias.");
        }
        List<ScheduledOccurrence> occurrences = new ArrayList<>();
        for (int index = 0; index < dates.size(); index++) {
            occurrences.add(ScheduledOccurrence.scheduled(ids.newId(), payment.id(), index + 1, dates.get(index)));
        }
        persistence.insert(payment, occurrences);
        events.publish(new ScheduledPaymentEvent("ScheduledPaymentCreated", payment.id(), null, payment.payerAccountId(),
                payment.status().name(), payment.firstDate(), payment.amount(), null, false, null));
        return payment;
    }

    @Override
    public ScheduledPayment get(Caller caller, UUID id) {
        return load(caller, id);
    }

    @Override
    public PageResult<ScheduledPayment> list(Caller caller, UUID payerAccountId, PageQuery page) {
        if (!caller.canAccess(payerAccountId)) {
            throw new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada.");
        }
        return persistence.listByPayer(payerAccountId, page);
    }

    @Override
    public List<ScheduledOccurrence> occurrences(Caller caller, UUID id) {
        load(caller, id);
        return persistence.occurrencesOf(id);
    }

    @Override
    public ScheduledPayment cancel(Caller caller, UUID id, ExpectedVersion expected) {
        ScheduledPayment payment = load(caller, id);
        if (!expected.matches(payment.version())) {
            throw new PreconditionFailedException("O agendamento foi alterado por outra requisição (versão desatualizada).");
        }
        ScheduledPayment cancelled = persistence.save(payment.cancelled());
        for (ScheduledOccurrence occurrence : persistence.occurrencesOf(id)) {
            if (occurrence.status().isCancellable()) {
                ScheduledOccurrence saved = persistence.saveOccurrence(occurrence.cancelled());
                publish("ScheduledOccurrenceCancelled", cancelled, saved, false);
            }
        }
        events.publish(new ScheduledPaymentEvent("ScheduledPaymentCancelled", id, null, cancelled.payerAccountId(), cancelled.status().name(),
                null, cancelled.amount(), null, false, null));
        return cancelled;
    }

    @Override
    public ScheduledOccurrence cancelOccurrence(Caller caller, UUID id, UUID occurrenceId) {
        ScheduledPayment payment = load(caller, id);
        ScheduledOccurrence occurrence = persistence.findOccurrence(occurrenceId).filter(candidate -> candidate.scheduledPaymentId().equals(id))
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Ocorrência não encontrada."));
        ScheduledOccurrence saved = persistence.saveOccurrence(occurrence.cancelled());
        publish("ScheduledOccurrenceCancelled", payment, saved, false);
        if (persistence.countOpen(id) == 0) {
            ScheduledPayment done = persistence.save(payment.completed());
            events.publish(new ScheduledPaymentEvent("ScheduledPaymentCompleted", id, null, done.payerAccountId(), done.status().name(), null,
                    done.amount(), null, false, null));
        }
        return saved;
    }

    private ScheduledPayment load(Caller caller, UUID id) {
        return persistence.findById(id).filter(scheduledPayment -> caller.canAccess(scheduledPayment.payerAccountId()))
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Agendamento não encontrado."));
    }

    private void publish(String type, ScheduledPayment payment, ScheduledOccurrence occurrence, boolean notify) {
        events.publish(new ScheduledPaymentEvent(type, payment.id(), occurrence.id(), payment.payerAccountId(), occurrence.status().name(), occurrence.dueDate(),
                payment.amount(), occurrence.nextAttemptAt(), notify, occurrence.paymentOrderId()));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(properties.zone()));
    }
}
