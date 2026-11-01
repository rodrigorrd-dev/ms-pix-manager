package br.com.pixmanager.api.rest.pixkey;

import br.com.pixmanager.api.rest.pixkey.dto.request.DeletePixKeyRequest;
import br.com.pixmanager.api.rest.pixkey.dto.request.LookupPixKeyRequest;
import br.com.pixmanager.api.rest.pixkey.dto.response.PixKeyLookupResponse;
import br.com.pixmanager.api.rest.pixkey.dto.response.PixKeyResponse;
import br.com.pixmanager.api.rest.pixkey.dto.request.RegisterPixKeyRequest;
import br.com.pixmanager.api.rest.pixkey.dto.request.VerifyOwnershipRequest;
import br.com.pixmanager.api.rest.pixkey.dto.response.OwnershipChallengeResponse;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.shared.security.ScopeAuthorization;
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

import java.util.UUID;

/**
 * Contrato da API de gestão de chaves (docs/05 §3). Contrato (interface) separado do comportamento ({@link PixKeyControllerImpl}):
 * um {@code /v2} nasce como nova interface sem tocar o {@code /v1}. Exclusão = transição substantiva ({@code /deletion}),
 * nunca {@code DELETE} com corpo.
 */
@RequestMapping("/v1/pix-keys")
@Validated
public interface PixKeyController {

    @PostMapping
    @PreAuthorize(ScopeAuthorization.PIXKEY_WRITE)
    ResponseEntity<PixKeyResponse> register(@Valid @RequestBody RegisterPixKeyRequest request,
                                            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey);

    @GetMapping("/{id}")
    @PreAuthorize(ScopeAuthorization.PIXKEY_READ)
    ResponseEntity<PixKeyResponse> get(@PathVariable UUID id,
                                       @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch);

    @GetMapping
    @PreAuthorize(ScopeAuthorization.PIXKEY_READ)
    ResponseEntity<PageResult<PixKeyResponse>> list(@RequestParam UUID accountId,
                                                    @RequestParam(required = false) PixKeyStatus status,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size);

    /** Chave PHONE/EMAIL em PENDING_OWNERSHIP_PROOF: envia um código ao dono do canal (202). Espera mínima entre pedidos ⇒ 429 + Retry-After. */
    @PostMapping("/{id}/ownership-proof/challenges")
    @PreAuthorize(ScopeAuthorization.PIXKEY_WRITE)
    ResponseEntity<OwnershipChallengeResponse> requestOwnershipProof(@PathVariable UUID id);

    /** Confere o código: acertando, a chave vai a PENDING_DICT (e o job a registra no DICT). Errando, conta tentativa (422, com as restantes). */
    @PostMapping("/{id}/ownership-proof/verifications")
    @PreAuthorize(ScopeAuthorization.PIXKEY_WRITE)
    ResponseEntity<PixKeyResponse> verifyOwnershipProof(@PathVariable UUID id, @Valid @RequestBody VerifyOwnershipRequest request);

    @PostMapping("/{id}/deletion")
    @PreAuthorize(ScopeAuthorization.PIXKEY_WRITE)
    ResponseEntity<PixKeyResponse> delete(@PathVariable UUID id,
                                          @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                          @Valid @RequestBody DeletePixKeyRequest request);

    /** POST (não GET): o valor da chave é PII e não pode ir em URL/log de acesso. */
    @PostMapping("/lookups")
    @PreAuthorize(ScopeAuthorization.PIXKEY_READ)
    ResponseEntity<PixKeyLookupResponse> lookup(@Valid @RequestBody LookupPixKeyRequest request);
}
