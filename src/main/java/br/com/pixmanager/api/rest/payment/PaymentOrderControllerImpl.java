package br.com.pixmanager.api.rest.payment;

import br.com.pixmanager.api.rest.payment.dto.request.CreatePaymentOrderRequest;
import br.com.pixmanager.api.rest.payment.dto.request.DecodeBrCodeRequest;
import br.com.pixmanager.api.rest.payment.dto.response.BrCodeResponse;
import br.com.pixmanager.api.rest.payment.dto.response.PaymentOrderResponse;
import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.port.in.payment.CancelPaymentOrderUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.PaymentOrderResult;
import br.com.pixmanager.core.port.in.payment.DecodeBrCodeUseCase;
import br.com.pixmanager.core.port.in.payment.FindPaymentOrderUseCase;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.util.ETags;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.exception.handler.ProblemDetails;
import br.com.pixmanager.shared.idempotency.IdempotentExecutor;
import br.com.pixmanager.shared.mappers.PaymentOrderMapper;
import br.com.pixmanager.shared.model.ExpectedVersion;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.shared.properties.ErrorProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RestController
public class PaymentOrderControllerImpl implements PaymentOrderController {

    private final CreatePaymentOrderUseCase create;
    private final FindPaymentOrderUseCase find;
    private final CancelPaymentOrderUseCase cancel;
    private final DecodeBrCodeUseCase decode;
    private final IdempotentExecutor idempotency;
    private final PaymentOrderMapper mapper;
    private final ErrorProperties errors;
    private final Clock clock;

    public PaymentOrderControllerImpl(CreatePaymentOrderUseCase create, FindPaymentOrderUseCase find, CancelPaymentOrderUseCase cancel,
                                      DecodeBrCodeUseCase decode, IdempotentExecutor idempotency, PaymentOrderMapper mapper,
                                      ErrorProperties errors, Clock clock) {
        this.create = create;
        this.find = find;
        this.cancel = cancel;
        this.decode = decode;
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.errors = errors;
        this.clock = clock;
    }

    @Override
    public ResponseEntity<?> create(String idempotencyKey, CreatePaymentOrderRequest request) {
        Caller caller = CallerContext.current();
        // fase 1 (DICT/HTTP) ANTES da transação da idempotência: a conexão do pool não fica presa durante a chamada externa
        var prepared = create.prepare(caller, mapper.toCommand(request));
        var result = idempotency.execute(caller.clientId(), "POST /v1/payment-orders", idempotencyKey, request, () -> {
            PaymentOrderResult outcome = create.submit(prepared);
            return switch (outcome) {
                case PaymentOrderResult.Created created -> new IdempotentExecutor.Response(HttpStatus.CREATED.value(), mapper.toResponse(created.order()));
                case PaymentOrderResult.Rejected rejected -> {
                    // recusa de NEGÓCIO é uma resposta como outra qualquer: gravada (e reproduzida) pela chave de idempotência
                    ProblemDetail pd = ProblemDetails.of(errors, HttpStatus.UNPROCESSABLE_ENTITY, "Pagamento recusado", rejected.detail(),
                            rejected.errorCode());
                    pd.setProperty("paymentOrderId", rejected.order().id().toString());
                    yield new IdempotentExecutor.Response(HttpStatus.UNPROCESSABLE_ENTITY.value(), pd);
                }
            };
        });
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore());
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        if (result.status() == HttpStatus.CREATED.value()) {
            builder.location(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(result.body().path("id").asText()).toUri());
        } else {
            builder.contentType(MediaType.APPLICATION_PROBLEM_JSON);
        }
        return builder.body(result.body());
    }

    @Override
    public ResponseEntity<PaymentOrderResponse> get(UUID id, String ifNoneMatch) {
        PaymentOrder order = find.find(CallerContext.current(), id)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Ordem não encontrada."));
        if (ETags.noneMatch(ifNoneMatch, order.version())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(String.valueOf(order.version())).build();
        }
        return ResponseEntity.ok().eTag(String.valueOf(order.version())).cacheControl(CacheControl.noStore()).body(mapper.toResponse(order));
    }

    @Override
    public ResponseEntity<PageResult<PaymentOrderResponse>> list(UUID payerAccountId, Instant from, Instant to, int page, int size) {
        Instant effectiveTo = to != null ? to : clock.instant();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(java.time.Duration.ofDays(30));
        PageResult<PaymentOrder> result = find.list(CallerContext.current(), payerAccountId, effectiveFrom, effectiveTo, PageQuery.of(page, size));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.map(mapper::toResponse));
    }

    @Override
    public ResponseEntity<PaymentOrderResponse> cancel(UUID id, String ifMatch) {
        PaymentOrder cancelled = cancel.cancel(CallerContext.current(), id, ExpectedVersion.parse(ifMatch));
        return ResponseEntity.ok().eTag(String.valueOf(cancelled.version())).cacheControl(CacheControl.noStore()).body(mapper.toResponse(cancelled));
    }

    @Override
    public ResponseEntity<BrCodeResponse> decode(DecodeBrCodeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapper.toResponse(decode.decode(request.payload())));
    }
}
