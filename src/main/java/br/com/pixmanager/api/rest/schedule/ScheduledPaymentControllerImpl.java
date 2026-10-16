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
    public ResponseEntity<?> create(String idempotencyKey, CreateScheduledPaymentRequest r) {
        var caller = CallerContext.current();
        var result = idempotency.execute(caller.clientId(), "POST /v1/scheduled-payments", idempotencyKey, r, () -> {
            var d = r.destination();
            ScheduledPayment created = schedules.create(caller, new CreateScheduledPaymentCommand(r.payerAccountId(), r.recurrence(), r.method(),
                    d == null ? null : d.pixKeyType(), d == null ? null : d.pixKey(), d == null ? null : d.ispb(), d == null ? null : d.branch(),
                    d == null ? null : d.account(), d == null ? null : d.accountType(), d == null ? null : d.name(),
                    d == null ? null : d.document(), Money.parseBcb(r.amount()), r.payerMessage(), r.firstDate(), r.endDate(), r.occurrencesTotal()));
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
        ScheduledPayment p = schedules.get(CallerContext.current(), id);
        if (ETags.noneMatch(ifNoneMatch, p.version())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(String.valueOf(p.version())).build();
        }
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).cacheControl(CacheControl.noStore()).body(toResponse(p));
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
        ScheduledPayment p = schedules.cancel(CallerContext.current(), id, ExpectedVersion.parse(ifMatch));
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).cacheControl(CacheControl.noStore()).body(toResponse(p));
    }

    @Override
    public ResponseEntity<ScheduledOccurrenceResponse> cancelOccurrence(UUID id, UUID occurrenceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(schedules.cancelOccurrence(CallerContext.current(), id, occurrenceId)));
    }

    private ScheduledPaymentResponse toResponse(ScheduledPayment p) {
        String keyMasked = p.pixKey() == null ? null : PixKeyValue.infer(p.pixKey()).map(PixKeyValue::masked).orElse(null);
        String docMasked = p.document() == null || p.document().isBlank() ? null : Document.of(p.document()).masked();
        return new ScheduledPaymentResponse(p.id(), p.payerAccountId(), p.recurrence(), p.method(), p.amount().toBcb(), p.firstDate(), p.endDate(),
                p.occurrencesTotal(), p.status(), new ScheduledPaymentResponse.Destination(p.pixKeyType(), keyMasked, p.name(), docMasked, p.ispb()),
                p.payerMessage(), p.version());
    }

    private ScheduledOccurrenceResponse toResponse(ScheduledOccurrence o) {
        return new ScheduledOccurrenceResponse(o.id(), o.sequenceNo(), o.dueDate(), o.status(), o.attemptCount(), o.nextAttemptAt(),
                o.customerNotifiedAt(), o.paymentOrderId(), o.lastErrorCode());
    }
}
