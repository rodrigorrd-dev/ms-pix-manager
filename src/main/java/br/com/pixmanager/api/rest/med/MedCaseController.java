package br.com.pixmanager.api.rest.med;

import br.com.pixmanager.api.rest.med.dto.request.MedRequests;
import br.com.pixmanager.api.rest.med.dto.response.MedResponses;
import br.com.pixmanager.api.rest.security.ScopeAuthorization;
import br.com.pixmanager.core.domain.enums.MedStatus;
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

/**
 * MED (docs/05). Back-office: escopos {@code med.read}/{@code med.write}. Papéis: REPORTER (nosso cliente pagou e reporta) e REPORTED
 * (nosso cliente recebeu). Transições por verbo no recurso (aceite, recusa, cancelamento, devolução, encerramento), nunca por PATCH de status.
 */
@RequestMapping("/v1/med-cases")
@Validated
public interface MedCaseController {

    /** 201. 404 Pix inexistente; 422 {@code PIX-422-071} fora dos 80 dias; 409 já há caso em andamento para o Pix/camada. */
    @PostMapping
    @PreAuthorize(ScopeAuthorization.MED_WRITE)
    ResponseEntity<?> open(@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody MedRequests.Open request);

    @GetMapping("/{id}")
    @PreAuthorize(ScopeAuthorization.MED_READ)
    ResponseEntity<MedResponses.Case> get(@PathVariable UUID id);

    @GetMapping
    @PreAuthorize(ScopeAuthorization.MED_READ)
    ResponseEntity<PageResult<MedResponses.Case>> list(@RequestParam(required = false) MedStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size);

    @GetMapping("/{id}/history")
    @PreAuthorize(ScopeAuthorization.MED_READ)
    ResponseEntity<List<MedResponses.History>> history(@PathVariable UUID id);

    @PostMapping("/{id}/acceptance")
    @PreAuthorize(ScopeAuthorization.MED_WRITE)
    ResponseEntity<MedResponses.Case> accept(@PathVariable UUID id, @Valid @RequestBody(required = false) MedRequests.Note body);

    @PostMapping("/{id}/refusal")
    @PreAuthorize(ScopeAuthorization.MED_WRITE)
    ResponseEntity<MedResponses.Case> refuse(@PathVariable UUID id, @Valid @RequestBody(required = false) MedRequests.Note body);

    @PostMapping("/{id}/cancellation")
    @PreAuthorize(ScopeAuthorization.MED_WRITE)
    ResponseEntity<MedResponses.Case> cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) MedRequests.Note body);

    /** Libera o bloqueio e devolve o valor bloqueado com natureza MED. */
    @PostMapping("/{id}/refund")
    @PreAuthorize(ScopeAuthorization.MED_WRITE)
    ResponseEntity<MedResponses.Case> refund(@PathVariable UUID id);

    @PostMapping("/{id}/closure")
    @PreAuthorize(ScopeAuthorization.MED_WRITE)
    ResponseEntity<MedResponses.Case> close(@PathVariable UUID id, @Valid @RequestBody(required = false) MedRequests.Note body);

    /** 422 {@code PIX-422-070} fora dos 80 dias da devolução. */
    @PostMapping("/{id}/contestations")
    @PreAuthorize(ScopeAuthorization.MED_WRITE)
    ResponseEntity<MedResponses.Contestation> contest(@PathVariable UUID id, @Valid @RequestBody MedRequests.Contestation body);

    @PostMapping("/contestations/{contestationId}/decision")
    @PreAuthorize(ScopeAuthorization.MED_WRITE)
    ResponseEntity<MedResponses.Contestation> decide(@PathVariable UUID contestationId, @Valid @RequestBody MedRequests.Decision body);
}
