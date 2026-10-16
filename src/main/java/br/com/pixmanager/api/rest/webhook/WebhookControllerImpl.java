package br.com.pixmanager.api.rest.webhook;

import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.api.rest.webhook.dto.request.WebhookRequest;
import br.com.pixmanager.api.rest.webhook.dto.response.WebhookPageResponse;
import br.com.pixmanager.api.rest.webhook.dto.response.WebhookResponse;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.port.in.webhook.ManageWebhookUseCase;
import br.com.pixmanager.core.port.in.webhook.ManageWebhookUseCase.Registered;
import br.com.pixmanager.core.port.out.persistence.WebhookSubscriptionPortOut.WebhookView;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class WebhookControllerImpl implements WebhookController {

    private final ManageWebhookUseCase webhooks;

    WebhookControllerImpl(ManageWebhookUseCase webhooks) {
        this.webhooks = webhooks;
    }

    @Override
    public ResponseEntity<WebhookResponse> put(String key, WebhookRequest request) {
        Registered r = webhooks.put(CallerContext.current(), key, request.webhookUrl());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(new WebhookResponse(r.subscription().url(), r.keyValue(), r.subscription().createdAt()));
    }

    @Override
    public ResponseEntity<WebhookResponse> get(String key) {
        WebhookView view = webhooks.get(CallerContext.current(), key)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Webhook não encontrado para a chave."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(view));
    }

    @Override
    public ResponseEntity<Void> delete(String key) {
        Caller caller = CallerContext.current();
        if (!webhooks.delete(caller, key)) {
            throw new NotFoundException(ErrorCodes.NOT_FOUND, "Webhook não encontrado para a chave.");
        }
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<WebhookPageResponse> list(Instant from, Instant to, int page, int pageSize) {
        if (from.isAfter(to)) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "inicio deve ser anterior ou igual a fim.");
        }
        PageResult<WebhookView> result = webhooks.list(CallerContext.current(), from, to, PageQuery.of(page, pageSize));
        var body = new WebhookPageResponse(
                new WebhookPageResponse.Parameters(from, to, new WebhookPageResponse.Pagination(result.page(), result.size(), result.totalPages(), result.totalElements())),
                result.content().stream().map(WebhookControllerImpl::toResponse).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static WebhookResponse toResponse(WebhookView v) {
        return new WebhookResponse(v.subscription().url(), v.keyValue(), v.subscription().createdAt());
    }
}
