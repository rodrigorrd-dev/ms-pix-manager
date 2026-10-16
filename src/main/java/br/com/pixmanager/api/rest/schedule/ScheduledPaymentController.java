package br.com.pixmanager.api.rest.schedule;

import br.com.pixmanager.api.rest.schedule.dto.request.CreateScheduledPaymentRequest;
import br.com.pixmanager.api.rest.schedule.dto.response.ScheduledOccurrenceResponse;
import br.com.pixmanager.api.rest.schedule.dto.response.ScheduledPaymentResponse;
import br.com.pixmanager.api.rest.security.ScopeAuthorization;
import br.com.pixmanager.shared.model.dto.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/** Pix Agendado (docs/05 §3). Criação com {@code Idempotency-Key} OBRIGATÓRIA; cancelamento por transição, com {@code If-Match}. */
@RequestMapping("/v1/scheduled-payments")
@Validated
public interface ScheduledPaymentController {

    /** 201 criado (com todas as ocorrências); 422 {@code PIX-422-061} agendamento inválido (data passada, recorrência inconsistente). */
    @PostMapping
    @PreAuthorize(ScopeAuthorization.SCHEDULE_WRITE)
    ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody CreateScheduledPaymentRequest request);

    @GetMapping("/{id}")
    @PreAuthorize(ScopeAuthorization.SCHEDULE_READ)
    ResponseEntity<ScheduledPaymentResponse> get(@PathVariable UUID id,
                                                 @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch);

    @GetMapping
    @PreAuthorize(ScopeAuthorization.SCHEDULE_READ)
    ResponseEntity<PageResult<ScheduledPaymentResponse>> list(@RequestParam UUID payerAccountId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size);

    @GetMapping("/{id}/occurrences")
    @PreAuthorize(ScopeAuthorization.SCHEDULE_READ)
    ResponseEntity<List<ScheduledOccurrenceResponse>> occurrences(@PathVariable UUID id);

    /** Cancela o agendamento e as ocorrências que ainda aguardam (BR-SCH-04). */
    @PostMapping("/{id}/cancellation")
    @PreAuthorize(ScopeAuthorization.SCHEDULE_WRITE)
    ResponseEntity<ScheduledPaymentResponse> cancel(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch);

    /** 422 {@code PIX-422-062} se a ocorrência já está em processamento ou terminou. */
    @PostMapping("/{id}/occurrences/{occurrenceId}/cancellation")
    @PreAuthorize(ScopeAuthorization.SCHEDULE_WRITE)
    ResponseEntity<ScheduledOccurrenceResponse> cancelOccurrence(@PathVariable UUID id, @PathVariable UUID occurrenceId);
}
