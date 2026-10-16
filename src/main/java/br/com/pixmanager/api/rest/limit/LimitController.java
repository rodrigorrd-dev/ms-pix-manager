package br.com.pixmanager.api.rest.limit;

import br.com.pixmanager.api.rest.limit.dto.request.ChangeLimitRequest;
import br.com.pixmanager.api.rest.limit.dto.response.LimitResponse;
import br.com.pixmanager.api.rest.limit.dto.response.LimitsResponse;
import br.com.pixmanager.api.rest.security.ScopeAuthorization;
import br.com.pixmanager.core.domain.enums.LimitScope;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;

/**
 * Limites Pix da conta (docs/05 §3): aumento só vale após a carência (24–48 h), redução é imediata, até o teto do banco.
 * {@code If-Match} obrigatório quando o limite já foi configurado (428 se ausente).
 */
@RequestMapping("/v1/limits/accounts/{accountId}")
@Validated
public interface LimitController {

    @GetMapping
    @PreAuthorize(ScopeAuthorization.LIMIT_READ)
    ResponseEntity<LimitsResponse> list(@PathVariable UUID accountId);

    @PutMapping("/{scope}")
    @PreAuthorize(ScopeAuthorization.LIMIT_WRITE)
    ResponseEntity<LimitResponse> change(@PathVariable UUID accountId, @PathVariable LimitScope scope,
                                         @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                         @Valid @RequestBody ChangeLimitRequest request);
}
