package br.com.pixmanager.core.port.in.schedule;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.ScheduledOccurrence;
import br.com.pixmanager.core.domain.entity.ScheduledPayment;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.enums.Recurrence;
import br.com.pixmanager.shared.model.ExpectedVersion;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ManageScheduledPaymentUseCase {

    ScheduledPayment create(Caller caller, CreateScheduledPaymentCommand command);

    ScheduledPayment get(Caller caller, UUID id);

    PageResult<ScheduledPayment> list(Caller caller, UUID payerAccountId, PageQuery page);

    List<ScheduledOccurrence> occurrences(Caller caller, UUID id);

    /** Cancela o agendamento e todas as ocorrências ainda canceláveis. */
    ScheduledPayment cancel(Caller caller, UUID id, ExpectedVersion expected);

    ScheduledOccurrence cancelOccurrence(Caller caller, UUID id, UUID occurrenceId);

    record CreateScheduledPaymentCommand(UUID payerAccountId, Recurrence recurrence, PaymentMethod method, PixKeyType pixKeyType,
                                         String pixKey, String ispb, String branch, String account, String accountType, String name,
                                         String document, Money amount, String payerMessage, LocalDate firstDate, LocalDate endDate,
                                         Integer occurrencesTotal) {}
}
