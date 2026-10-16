package br.com.pixmanager.api.rest.refund;

import br.com.pixmanager.api.rest.refund.dto.request.RefundRequest;
import br.com.pixmanager.api.rest.refund.dto.response.RefundResponse;
import br.com.pixmanager.api.rest.security.ScopeAuthorization;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** Devolução de Pix recebido — API Pix do BCB 2.10.0. Idempotente por {@code (e2eid, id)} (BR-REF-01). */
@RequestMapping("/api/pix/v2/pix/{e2eid}/devolucao")
@Validated
public interface RefundController {

    /** 201 na primeira vez; 200 com o MESMO recurso em repetição idêntica; 422 se o id já foi usado com outro corpo. */
    @PutMapping("/{id}")
    @PreAuthorize(ScopeAuthorization.PIX_WRITE)
    ResponseEntity<RefundResponse> request(@PathVariable("e2eid") String endToEndId, @PathVariable("id") String id,
                                           @Valid @RequestBody RefundRequest request);

    @GetMapping("/{id}")
    @PreAuthorize(ScopeAuthorization.PIX_READ)
    ResponseEntity<RefundResponse> find(@PathVariable("e2eid") String endToEndId, @PathVariable("id") String id);
}
