package br.com.pixmanager.api.rest.refund;

import br.com.pixmanager.api.rest.refund.dto.request.RefundRequest;
import br.com.pixmanager.api.rest.refund.dto.response.RefundResponse;
import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.port.in.refund.FindRefundUseCase;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase.RequestRefundCommand;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase.RequestRefundResult;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.mappers.RefundMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class RefundControllerImpl implements RefundController {

    private final RequestRefundUseCase request;
    private final FindRefundUseCase find;
    private final RefundMapper mapper;

    public RefundControllerImpl(RequestRefundUseCase request, FindRefundUseCase find, RefundMapper mapper) {
        this.request = request;
        this.find = find;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<RefundResponse> request(String endToEndId, String id, RefundRequest body) {
        RequestRefundResult result = request.request(CallerContext.current(),
                new RequestRefundCommand(endToEndId, id, Money.parseBcb(body.amount()), body.nature(), body.description()));
        return switch (result) {
            case RequestRefundResult.Created created -> ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequestUri().build().toUri())
                    .cacheControl(CacheControl.noStore()).body(mapper.toResponse(created.refund()));
            case RequestRefundResult.Replayed replayed -> ResponseEntity.status(HttpStatus.OK).cacheControl(CacheControl.noStore())
                    .body(mapper.toResponse(replayed.refund()));
        };
    }

    @Override
    public ResponseEntity<RefundResponse> find(String endToEndId, String id) {
        var refund = find.find(CallerContext.current(), endToEndId, id)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Devolução não encontrada."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapper.toResponse(refund));
    }
}
