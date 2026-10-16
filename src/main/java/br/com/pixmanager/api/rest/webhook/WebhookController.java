package br.com.pixmanager.api.rest.webhook;

import br.com.pixmanager.api.rest.security.ScopeAuthorization;
import br.com.pixmanager.api.rest.webhook.dto.request.WebhookRequest;
import br.com.pixmanager.api.rest.webhook.dto.response.WebhookPageResponse;
import br.com.pixmanager.api.rest.webhook.dto.response.WebhookResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;

/**
 * Webhook de Pix recebido, no formato da API Pix do BCB. A chave vai no CAMINHO (como o BCB exige), então aparece em log de acesso do gateway:
 * mascare esse caminho no ingress. Chave de outra conta ⇒ 404. {@code PUT} cria (201) ou atualiza (200); a URL é validada contra SSRF (422).
 */
@RequestMapping("/api/pix/v2/webhook")
@Validated
public interface WebhookController {

    @PutMapping("/{chave}")
    @PreAuthorize(ScopeAuthorization.WEBHOOK_WRITE)
    ResponseEntity<WebhookResponse> put(@PathVariable("chave") @NotBlank @Size(max = 77) String key, @Valid @RequestBody WebhookRequest request);

    @GetMapping("/{chave}")
    @PreAuthorize(ScopeAuthorization.WEBHOOK_READ)
    ResponseEntity<WebhookResponse> get(@PathVariable("chave") @NotBlank @Size(max = 77) String key);

    @DeleteMapping("/{chave}")
    @PreAuthorize(ScopeAuthorization.WEBHOOK_WRITE)
    ResponseEntity<Void> delete(@PathVariable("chave") @NotBlank @Size(max = 77) String key);

    @GetMapping
    @PreAuthorize(ScopeAuthorization.WEBHOOK_READ)
    ResponseEntity<WebhookPageResponse> list(
            @RequestParam("inicio") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam("fim") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "paginacao.paginaAtual", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "paginacao.itensPorPagina", defaultValue = "100") @Min(1) @Max(100) int pageSize);
}
