package br.com.pixmanager.api.rest.med;

import br.com.pixmanager.api.rest.med.dto.request.MedRequests;
import br.com.pixmanager.api.rest.med.dto.response.MedResponses;
import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.core.domain.entity.MedCase;
import br.com.pixmanager.core.domain.entity.MedContestation;
import br.com.pixmanager.core.domain.enums.MedStatus;
import br.com.pixmanager.core.port.in.med.ManageMedCaseUseCase;
import br.com.pixmanager.core.port.in.med.ManageMedCaseUseCase.OpenMedCaseCommand;
import br.com.pixmanager.shared.idempotency.IdempotentExecutor;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
public class MedCaseControllerImpl implements MedCaseController {

    private final ManageMedCaseUseCase med;
    private final IdempotentExecutor idempotency;

    public MedCaseControllerImpl(ManageMedCaseUseCase med, IdempotentExecutor idempotency) {
        this.med = med;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<?> open(String idempotencyKey, MedRequests.Open r) {
        var caller = CallerContext.current();
        var result = idempotency.execute(caller.clientId(), "POST /v1/med-cases", idempotencyKey, r,
                () -> new IdempotentExecutor.Response(HttpStatus.CREATED.value(),
                        toResponse(med.open(caller, new OpenMedCaseCommand(r.role(), r.endToEndId(), r.caseType(), r.description(), r.layer())))));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore());
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.location(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(result.body().path("id").asText()).toUri())
                .body(result.body());
    }

    @Override
    public ResponseEntity<MedResponses.Case> get(UUID id) {
        return ok(med.get(CallerContext.current(), id));
    }

    @Override
    public ResponseEntity<PageResult<MedResponses.Case>> list(MedStatus status, int page, int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(med.list(CallerContext.current(), status, PageQuery.of(page, size)).map(this::toResponse));
    }

    @Override
    public ResponseEntity<List<MedResponses.History>> history(UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(med.history(CallerContext.current(), id).stream()
                .map(h -> new MedResponses.History(h.from(), h.to(), h.actor(), h.note(), h.occurredAt())).toList());
    }

    @Override
    public ResponseEntity<MedResponses.Case> accept(UUID id, MedRequests.Note body) {
        return ok(med.accept(CallerContext.current(), id, note(body)));
    }

    @Override
    public ResponseEntity<MedResponses.Case> refuse(UUID id, MedRequests.Note body) {
        return ok(med.refuse(CallerContext.current(), id, note(body)));
    }

    @Override
    public ResponseEntity<MedResponses.Case> cancel(UUID id, MedRequests.Note body) {
        return ok(med.cancel(CallerContext.current(), id, note(body)));
    }

    @Override
    public ResponseEntity<MedResponses.Case> refund(UUID id) {
        return ok(med.startRefund(CallerContext.current(), id));
    }

    @Override
    public ResponseEntity<MedResponses.Case> close(UUID id, MedRequests.Note body) {
        return ok(med.close(CallerContext.current(), id, note(body)));
    }

    @Override
    public ResponseEntity<MedResponses.Contestation> contest(UUID id, MedRequests.Contestation body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(toResponse(med.contest(CallerContext.current(), id, body.reason())));
    }

    @Override
    public ResponseEntity<MedResponses.Contestation> decide(UUID contestationId, MedRequests.Decision body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(toResponse(med.decideContestation(CallerContext.current(), contestationId, body.accepted(), body.note())));
    }

    private ResponseEntity<MedResponses.Case> ok(MedCase c) {
        return ResponseEntity.ok().eTag(String.valueOf(c.version())).cacheControl(CacheControl.noStore()).body(toResponse(c));
    }

    private static String note(MedRequests.Note body) {
        return body == null ? null : body.note();
    }

    private MedResponses.Case toResponse(MedCase c) {
        return new MedResponses.Case(c.id(), c.endToEndId(), c.accountId(), c.caseType(), c.role(), c.status(), c.layer(), c.description(), c.reportedAt(),
                c.analysisDeadlineAt(), c.trackedAmount().toBcb(), c.blockedAmount().toBcb(), c.refundId(), c.dictFundsRecoveryId(), c.version());
    }

    private MedResponses.Contestation toResponse(MedContestation c) {
        return new MedResponses.Contestation(c.id(), c.caseId(), c.refundId(), c.status(), c.reason(), c.refundedAt(), c.deadlineAt(), c.decidedAt());
    }
}
