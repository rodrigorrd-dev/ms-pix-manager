package br.com.pixmanager.shared.mappers;

import br.com.pixmanager.api.rest.payment.dto.request.CreatePaymentOrderRequest.Destination;
import br.com.pixmanager.api.rest.schedule.dto.request.CreateScheduledPaymentRequest;
import br.com.pixmanager.api.rest.schedule.dto.response.ScheduledOccurrenceResponse;
import br.com.pixmanager.api.rest.schedule.dto.response.ScheduledPaymentResponse;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.entity.ScheduledOccurrence;
import br.com.pixmanager.core.domain.entity.ScheduledPayment;
import br.com.pixmanager.core.port.in.schedule.ManageScheduledPaymentUseCase.CreateScheduledPaymentCommand;
import br.com.pixmanager.shared.model.document.Document;
import org.springframework.stereotype.Component;

@Component
public class ScheduledPaymentMapper {

    public CreateScheduledPaymentCommand toCommand(CreateScheduledPaymentRequest request) {
        Destination destination = Destination.orEmpty(request.destination());
        return new CreateScheduledPaymentCommand(request.payerAccountId(), request.recurrence(), request.method(),
                destination.pixKeyType(), destination.pixKey(), destination.ispb(), destination.branch(),
                destination.account(), destination.accountType(), destination.name(), destination.document(),
                Money.parseBcb(request.amount()), request.payerMessage(), request.firstDate(), request.endDate(), request.occurrencesTotal());
    }

    public ScheduledPaymentResponse toResponse(ScheduledPayment scheduledPayment) {
        String keyMasked = scheduledPayment.pixKey() == null ? null : PixKeyValue.infer(scheduledPayment.pixKey()).map(PixKeyValue::masked).orElse(null);
        String documentMasked = scheduledPayment.document() == null || scheduledPayment.document().isBlank() ? null : Document.of(scheduledPayment.document()).masked();
        ScheduledPaymentResponse.Destination destination = new ScheduledPaymentResponse.Destination(
                scheduledPayment.pixKeyType(), keyMasked, scheduledPayment.name(), documentMasked, scheduledPayment.ispb());
        return new ScheduledPaymentResponse(scheduledPayment.id(), scheduledPayment.payerAccountId(), scheduledPayment.recurrence(), scheduledPayment.method(),
                scheduledPayment.amount().toBcb(), scheduledPayment.firstDate(), scheduledPayment.endDate(), scheduledPayment.occurrencesTotal(),
                scheduledPayment.status(), destination, scheduledPayment.payerMessage(), scheduledPayment.version());
    }

    public ScheduledOccurrenceResponse toResponse(ScheduledOccurrence occurrence) {
        return new ScheduledOccurrenceResponse(occurrence.id(), occurrence.sequenceNo(), occurrence.dueDate(), occurrence.status(), occurrence.attemptCount(),
                occurrence.nextAttemptAt(), occurrence.customerNotifiedAt(), occurrence.paymentOrderId(), occurrence.lastErrorCode());
    }
}
