package br.com.pixmanager.api.rest.charge;

import br.com.pixmanager.api.rest.charge.dto.request.CobvRequest;
import br.com.pixmanager.api.rest.charge.dto.response.ChargeResponse;
import br.com.pixmanager.shared.security.ScopeAuthorization;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** Cobrança com vencimento ({@code cobv}) — API Pix do BCB 2.10.0. Multa/juros/desconto: backlog (docs/10). */
@RequestMapping("/api/pix/v2/cobv")
@Validated
public interface DueChargeController {

    @PutMapping("/{txid}")
    @PreAuthorize(ScopeAuthorization.COBV_WRITE)
    ResponseEntity<ChargeResponse> create(@PathVariable String txid, @Valid @RequestBody CobvRequest request);

    @GetMapping("/{txid}")
    @PreAuthorize(ScopeAuthorization.COBV_READ)
    ResponseEntity<ChargeResponse> find(@PathVariable String txid,
                                        @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch);
}
