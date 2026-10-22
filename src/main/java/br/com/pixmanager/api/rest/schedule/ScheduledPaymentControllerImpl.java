package br.com.pixmanager.api.rest.schedule;

import br.com.pixmanager.api.rest.schedule.dto.request.CreateScheduledPaymentRequest;
import br.com.pixmanager.api.rest.schedule.dto.response.ScheduledOccurrenceResponse;
import br.com.pixmanager.api.rest.schedule.dto.response.ScheduledPaymentResponse;
import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.core.domain.entity.ScheduledPayment;
import br.com.pixmanager.core.port.in.schedule.ManageScheduledPaymentUseCase;
import br.com.pixmanager.shared.idempotency.IdempotentExecutor;
import br.com.pixmanager.shared.mappers.ScheduledPaymentMapper;
import br.com.pixmanager.shared.model.ExpectedVersion;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
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
    private final ScheduledPaymentMapper mapper;

    public ScheduledPaymentControllerImpl(ManageScheduledPaymentUseCase schedules, IdempotentExecutor idempotency, ScheduledPaymentMapper mapper) {
        this.schedules = schedules;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<?> create(String idempotencyKey, CreateScheduledPaymentRequest request) {
        var caller = CallerContext.current();
        var result = idempotency.execute(caller.clientId(), "POST /v1/scheduled-payments", idempotencyKey, request, () -> {
            ScheduledPayment created = schedules.create(caller, mapper.toCommand(request));
            return new IdempotentExecutor.Response(HttpStatus.CREATED.value(), mapper.toResponse(created));
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
        return ResponseEntity.ok().eTag(String.valueOf(scheduledPayment.version())).cacheControl(CacheControl.noStore()).body(mapper.toResponse(scheduledPayment));
    }

    @Override
    public ResponseEntity<PageResult<ScheduledPaymentResponse>> list(UUID payerAccountId, int page, int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(schedules.list(CallerContext.current(), payerAccountId, PageQuery.of(page, size)).map(mapper::toResponse));
    }

    @Override
    public ResponseEntity<List<ScheduledOccurrenceResponse>> occurrences(UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(schedules.occurrences(CallerContext.current(), id).stream().map(mapper::toResponse).toList());
    }

    @Override
    public ResponseEntity<ScheduledPaymentResponse> cancel(UUID id, String ifMatch) {
        ScheduledPayment scheduledPayment = schedules.cancel(CallerContext.current(), id, ExpectedVersion.parse(ifMatch));
        return ResponseEntity.ok().eTag(String.valueOf(scheduledPayment.version())).cacheControl(CacheControl.noStore()).body(mapper.toResponse(scheduledPayment));
    }

    @Override
    public ResponseEntity<ScheduledOccurrenceResponse> cancelOccurrence(UUID id, UUID occurrenceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapper.toResponse(schedules.cancelOccurrence(CallerContext.current(), id, occurrenceId)));
    }

}
