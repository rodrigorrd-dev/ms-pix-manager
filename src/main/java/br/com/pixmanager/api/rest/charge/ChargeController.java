package br.com.pixmanager.api.rest.charge;

import br.com.pixmanager.api.rest.charge.dto.request.CobRequest;
import br.com.pixmanager.api.rest.charge.dto.request.CobRevisionRequest;
import br.com.pixmanager.api.rest.charge.dto.response.ChargePageResponse;
import br.com.pixmanager.api.rest.charge.dto.response.ChargeResponse;
import br.com.pixmanager.api.rest.security.ScopeAuthorization;
import br.com.pixmanager.core.domain.enums.ChargeStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;

/**
 * Cobrança imediata ({@code cob}) — superfície COMPATÍVEL com a API Pix do BCB 2.10.0 (rotas e campos literais).
 * Contrato (esta interface) separado do comportamento ({@link ChargeControllerImpl}), como no mesa-credito-full.
 */
@RequestMapping("/api/pix/v2/cob")
@Validated
public interface ChargeController {

    /** {@code txid} do cliente ({@code [a-zA-Z0-9]{26,35}}). Mesmo txid e mesmo corpo ⇒ 200 com o recurso existente. */
    @PutMapping("/{txid}")
    @PreAuthorize(ScopeAuthorization.COB_WRITE)
    ResponseEntity<ChargeResponse> create(@PathVariable String txid, @Valid @RequestBody CobRequest request);

    /** {@code txid} gerado pelo PSP. */
    @PostMapping
    @PreAuthorize(ScopeAuthorization.COB_WRITE)
    ResponseEntity<ChargeResponse> createWithGeneratedTxid(@Valid @RequestBody CobRequest request);

    /** Remoção/revisão; {@code If-Match} obrigatório (428 se ausente, 412 se desatualizado). */
    @PatchMapping("/{txid}")
    @PreAuthorize(ScopeAuthorization.COB_WRITE)
    ResponseEntity<ChargeResponse> revise(@PathVariable String txid,
                                          @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                          @Valid @RequestBody CobRevisionRequest request);

    @GetMapping("/{txid}")
    @PreAuthorize(ScopeAuthorization.COB_READ)
    ResponseEntity<ChargeResponse> find(@PathVariable String txid,
                                        @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch);

    @GetMapping
    @PreAuthorize(ScopeAuthorization.COB_READ)
    ResponseEntity<ChargePageResponse> search(
            @RequestParam("inicio") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam("fim") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "cpf", required = false) String cpf,
            @RequestParam(name = "cnpj", required = false) String cnpj,
            @RequestParam(name = "status", required = false) ChargeStatus status,
            @RequestParam(name = "locationPresente", required = false) Boolean locationPresent,
            @RequestParam(name = "paginacao.paginaAtual", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "paginacao.itensPorPagina", defaultValue = "100") @Min(1) @Max(100) int pageSize);
}
