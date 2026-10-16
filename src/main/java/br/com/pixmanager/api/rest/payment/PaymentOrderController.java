package br.com.pixmanager.api.rest.payment;

import br.com.pixmanager.api.rest.payment.dto.request.CreatePaymentOrderRequest;
import br.com.pixmanager.api.rest.payment.dto.request.DecodeBrCodeRequest;
import br.com.pixmanager.api.rest.payment.dto.response.BrCodeResponse;
import br.com.pixmanager.api.rest.payment.dto.response.PaymentOrderResponse;
import br.com.pixmanager.api.rest.security.ScopeAuthorization;
import br.com.pixmanager.shared.model.dto.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.Instant;
import java.util.UUID;

/** Ordens de pagamento Pix (docs/05 §3). Criação com {@code Idempotency-Key} OBRIGATÓRIA; cancelamento por transição, não por {@code DELETE}. */
@RequestMapping("/v1/payment-orders")
@Validated
public interface PaymentOrderController {

    /** 201 ordem criada; 422 recusa de negócio (limite etc.; a ordem fica REJECTED); repetição com a mesma chave devolve a MESMA resposta. */
    @PostMapping
    @PreAuthorize(ScopeAuthorization.PAYMENT_WRITE)
    ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody CreatePaymentOrderRequest request);

    @GetMapping("/{id}")
    @PreAuthorize(ScopeAuthorization.PAYMENT_READ)
    ResponseEntity<PaymentOrderResponse> get(@PathVariable UUID id,
                                             @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch);

    @GetMapping
    @PreAuthorize(ScopeAuthorization.PAYMENT_READ)
    ResponseEntity<PageResult<PaymentOrderResponse>> list(@RequestParam UUID payerAccountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size);

    @PostMapping("/{id}/cancellation")
    @PreAuthorize(ScopeAuthorization.PAYMENT_WRITE)
    ResponseEntity<PaymentOrderResponse> cancel(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch);

    /** Decodifica um BR Code (CRC validado) sem criar ordem. */
    @PostMapping("/decodings")
    @PreAuthorize(ScopeAuthorization.PAYMENT_READ)
    ResponseEntity<BrCodeResponse> decode(@Valid @RequestBody DecodeBrCodeRequest request);
}
