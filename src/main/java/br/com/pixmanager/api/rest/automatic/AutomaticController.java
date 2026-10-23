package br.com.pixmanager.api.rest.automatic;

import br.com.pixmanager.api.rest.automatic.dto.AutomaticDtos;
import br.com.pixmanager.shared.security.ScopeAuthorization;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * Pix Automático, lado pagador (docs/05). Escopos {@code automatic.read}/{@code automatic.write}. As rotas de entrada (solicitação e cobrança) existem para o
 * spi-gateway/back-office; os verbos de decisão do pagador seguem o padrão do projeto (transição por verbo, nunca PATCH de status).
 */
@RequestMapping("/v1/automatic-authorizations")
@Validated
public interface AutomaticController {

    /** 201; 404 conta; 422 documento do pagador diferente do titular; 409 idRec repetido. */
    @PostMapping
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_WRITE)
    ResponseEntity<AutomaticDtos.AuthorizationResponse> receive(@Valid @RequestBody AutomaticDtos.ReceiveAuthorization request);

    @GetMapping("/{id}")
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_READ)
    ResponseEntity<AutomaticDtos.AuthorizationResponse> get(@PathVariable UUID id);

    @GetMapping
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_READ)
    ResponseEntity<PageResult<AutomaticDtos.AuthorizationResponse>> list(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size);

    @PostMapping("/{id}/approval")
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_WRITE)
    ResponseEntity<AutomaticDtos.AuthorizationResponse> approve(@PathVariable UUID id, @Valid @RequestBody(required = false) AutomaticDtos.Approve body);

    @PostMapping("/{id}/rejection")
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_WRITE)
    ResponseEntity<AutomaticDtos.AuthorizationResponse> reject(@PathVariable UUID id, @Valid @RequestBody AutomaticDtos.Reject body);

    @PostMapping("/{id}/cancellation")
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_WRITE)
    ResponseEntity<AutomaticDtos.AuthorizationResponse> cancel(@PathVariable UUID id, @Valid @RequestBody AutomaticDtos.Cancel body);

    /** O pagador ajusta o teto de uma autorização aprovada, a qualquer tempo. */
    @PutMapping("/{id}/limit")
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_WRITE)
    ResponseEntity<AutomaticDtos.AuthorizationResponse> adjustLimit(@PathVariable UUID id, @Valid @RequestBody AutomaticDtos.Limit body);

    /** 201; 422 {@code PIX-422-060} autorização não aprovada; 422 vencimento/valor fora das regras; 409 txid repetido. */
    @PostMapping("/{id}/charges")
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_WRITE)
    ResponseEntity<AutomaticDtos.ChargeResponse> registerCharge(@PathVariable UUID id, @Valid @RequestBody AutomaticDtos.RegisterCharge body);

    @GetMapping("/{id}/charges")
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_READ)
    ResponseEntity<List<AutomaticDtos.ChargeResponse>> charges(@PathVariable UUID id);

    @GetMapping("/{id}/charges/{chargeId}/attempts")
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_READ)
    ResponseEntity<List<AutomaticDtos.AttemptResponse>> attempts(@PathVariable UUID id, @PathVariable UUID chargeId);

    /** Até as 23h59 da véspera do vencimento. */
    @PostMapping("/{id}/charges/{chargeId}/cancellation")
    @PreAuthorize(ScopeAuthorization.AUTOMATIC_WRITE)
    ResponseEntity<AutomaticDtos.ChargeResponse> cancelCharge(@PathVariable UUID id, @PathVariable UUID chargeId);
}
