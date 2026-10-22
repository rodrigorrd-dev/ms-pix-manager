package br.com.pixmanager.api.rest.schedule;

import br.com.pixmanager.api.rest.schedule.dto.request.CreateScheduledPaymentRequest;
import br.com.pixmanager.api.rest.schedule.dto.response.ScheduledOccurrenceResponse;
import br.com.pixmanager.api.rest.schedule.dto.response.ScheduledPaymentResponse;
import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.entity.ScheduledOccurrence;
import br.com.pixmanager.core.domain.entity.ScheduledPayment;
import br.com.pixmanager.core.port.in.schedule.ManageScheduledPaymentUseCase;
import br.com.pixmanager.core.port.in.schedule.ManageScheduledPaymentUseCase.CreateScheduledPaymentCommand;
import br.com.pixmanager.shared.idempotency.IdempotentExecutor;
import br.com.pixmanager.shared.model.ExpectedVersion;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.util.ETags;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
public class ScheduledPaymentControllerImpl implements ScheduledPaymentController {

    private final ManageScheduledPaymentUseCase schedules;
    private final IdempotentExecutor idempotency;

    public ScheduledPaymentControllerImpl(ManageScheduledPaymentUseCase schedules, IdempotentExecutor idempotency) {
        this.schedules = schedules;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<?> create(String idempotencyKey, CreateScheduledPaymentRequest request) {
        var caller = CallerContext.current();
        var result = idempotency.execute(caller.clientId(), "POST /v1/scheduled-payments", idempotencyKey, request, () -> {
            var d = request.destination();
            ScheduledPayment created = schedules.create(caller, new CreateScheduledPaymentCommand(request.payerAccountId(), request.recurrence(), request.method(),
                    d == null ? null : d.pixKeyType(), d == null ? null : d.pixKey(), d == null ? null : d.ispb(), d == null ? null : d.branch(),
                    d == null ? null : d.account(), d == null ? null : d.accountType(), d == null ? null : d.name(),
                    d == null ? null : d.document(), Money.parseBcb(request.amount()), request.payerMessage(), request.firstDate(), request.endDate(), request.occurrencesTotal()));
            return new IdempotentExecutor.Response(HttpStatus.CREATED.value(), toResponse(created));
        });
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore());
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.location(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(result.body().path("id").asText()).toUri())
                .body(result.body());
    }

    @Override
    public ResponseEntity<ScheduledPaymentResponse> get(UUID id, String ifNoneMatch) {
        ScheduledPayment scheduledPayment = schedules.get(CallerContext.current(), id);
        if (ETags.noneMatch(ifNoneMatch, scheduledPayment.version())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(String.valueOf(scheduledPayment.version())).build();
        }
        return ResponseEntity.ok().eTag(String.valueOf(scheduledPayment.version())).cacheControl(CacheControl.noStore()).body(toResponse(scheduledPayment));
    }

    @Override
    public ResponseEntity<PageResult<ScheduledPaymentResponse>> list(UUID payerAccountId, int page, int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(schedules.list(CallerContext.current(), payerAccountId, PageQuery.of(page, size)).map(this::toResponse));
    }

    @Override
    public ResponseEntity<List<ScheduledOccurrenceResponse>> occurrences(UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(schedules.occurrences(CallerContext.current(), id).stream().map(this::toResponse).toList());
    }

    @Override
    public ResponseEntity<ScheduledPaymentResponse> cancel(UUID id, String ifMatch) {
        ScheduledPayment scheduledPayment = schedules.cancel(CallerContext.current(), id, ExpectedVersion.parse(ifMatch));
        return ResponseEntity.ok().eTag(String.valueOf(scheduledPayment.version())).cacheControl(CacheControl.noStore()).body(toResponse(scheduledPayment));
    }

    @Override
    public ResponseEntity<ScheduledOccurrenceResponse> cancelOccurrence(UUID id, UUID occurrenceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(schedules.cancelOccurrence(CallerContext.current(), id, occurrenceId)));
    }

    private ScheduledPaymentResponse toResponse(ScheduledPayment scheduledPayment) {
        String keyMasked = scheduledPayment.pixKey() == null ? null : PixKeyValue.infer(scheduledPayment.pixKey()).map(PixKeyValue::masked).orElse(null);
        String docMasked = scheduledPayment.document() == null || scheduledPayment.document().isBlank() ? null : Document.of(scheduledPayment.document()).masked();
        return new ScheduledPaymentResponse(scheduledPayment.id(), scheduledPayment.payerAccountId(), scheduledPayment.recurrence(), scheduledPayment.method(), scheduledPayment.amount().toBcb(), scheduledPayment.firstDate(), scheduledPayment.endDate(),
                scheduledPayment.occurrencesTotal(), scheduledPayment.status(), new ScheduledPaymentResponse.Destination(scheduledPayment.pixKeyType(), keyMasked, scheduledPayment.name(), docMasked, scheduledPayment.ispb()),
                scheduledPayment.payerMessage(), scheduledPayment.version());
    }

    private ScheduledOccurrenceResponse toResponse(ScheduledOccurrence occurrence) {
        return new ScheduledOccurrenceResponse(occurrence.id(), occurrence.sequenceNo(), occurrence.dueDate(), occurrence.status(), occurrence.attemptCount(), occurrence.nextAttemptAt(),
                occurrence.customerNotifiedAt(), occurrence.paymentOrderId(), occurrence.lastErrorCode());
    }
}
