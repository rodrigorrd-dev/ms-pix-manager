package br.com.pixmanager.api.rest.automatic;

import br.com.pixmanager.api.rest.automatic.dto.AutomaticDtos;
import br.com.pixmanager.shared.security.CallerContext;
import br.com.pixmanager.core.domain.entity.AutomaticAttempt;
import br.com.pixmanager.core.domain.entity.AutomaticAuthorization;
import br.com.pixmanager.core.domain.entity.AutomaticCharge;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.port.in.automatic.ManageAutomaticUseCase;
import br.com.pixmanager.core.port.in.automatic.ManageAutomaticUseCase.ReceiveAuthorizationCommand;
import br.com.pixmanager.core.port.in.automatic.ManageAutomaticUseCase.RegisterChargeCommand;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
public class AutomaticControllerImpl implements AutomaticController {

    private final ManageAutomaticUseCase automatic;

    public AutomaticControllerImpl(ManageAutomaticUseCase automatic) {
        this.automatic = automatic;
    }

    @Override
    public ResponseEntity<AutomaticDtos.AuthorizationResponse> receive(AutomaticDtos.ReceiveAuthorization receiveAuthorization) {
        AutomaticAuthorization authorization = automatic.receiveAuthorization(CallerContext.current(),
                new ReceiveAuthorizationCommand(receiveAuthorization.idRec(), receiveAuthorization.payerAccountId(), receiveAuthorization.payerDocument(),
                receiveAuthorization.receiverDocument(), receiveAuthorization.receiverName(), receiveAuthorization.contract(), receiveAuthorization.objectDescription(), receiveAuthorization.periodicity(), receiveAuthorization.startDate(), receiveAuthorization.endDate(), Money.parseBcbOrNull(receiveAuthorization.fixedAmount()),
                Money.parseBcbOrNull(receiveAuthorization.receiverMinAmount())));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .location(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(authorization.id()).toUri()).body(toResponse(authorization));
    }

    @Override
    public ResponseEntity<AutomaticDtos.AuthorizationResponse> get(UUID id) {
        return ok(automatic.getAuthorization(CallerContext.current(), id));
    }

    @Override
    public ResponseEntity<PageResult<AutomaticDtos.AuthorizationResponse>> list(String status, int page, int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(automatic.listAuthorizations(CallerContext.current(), status, PageQuery.of(page, size)).map(this::toResponse));
    }

    @Override
    public ResponseEntity<AutomaticDtos.AuthorizationResponse> approve(UUID id, AutomaticDtos.Approve body) {
        return ok(automatic.approve(CallerContext.current(), id, body == null ? null : Money.parseBcbOrNull(body.payerMaxAmount())));
    }

    @Override
    public ResponseEntity<AutomaticDtos.AuthorizationResponse> reject(UUID id, AutomaticDtos.Reject body) {
        return ok(automatic.reject(CallerContext.current(), id, body.rejectionCode()));
    }

    @Override
    public ResponseEntity<AutomaticDtos.AuthorizationResponse> cancel(UUID id, AutomaticDtos.Cancel body) {
        return ok(automatic.cancel(CallerContext.current(), id, body.cancelledBy()));
    }

    @Override
    public ResponseEntity<AutomaticDtos.AuthorizationResponse> adjustLimit(UUID id, AutomaticDtos.Limit body) {
        return ok(automatic.adjustLimit(CallerContext.current(), id, Money.parseBcbOrNull(body.payerMaxAmount())));
    }

    @Override
    public ResponseEntity<AutomaticDtos.ChargeResponse> registerCharge(UUID id, AutomaticDtos.RegisterCharge registerCharge) {
        AutomaticCharge automaticCharge = automatic.registerCharge(CallerContext.current(), id, new RegisterChargeCommand(registerCharge.txid(),
                registerCharge.dueDate(), Money.parseBcbOrNull(registerCharge.amount()), registerCharge.receiverIspb(),
                registerCharge.receiverBranch(), registerCharge.receiverAccount(), registerCharge.receiverAccountType()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(toResponse(automaticCharge));
    }

    @Override
    public ResponseEntity<List<AutomaticDtos.ChargeResponse>> charges(UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(automatic.charges(CallerContext.current(), id).stream().map(this::toResponse).toList());
    }

    @Override
    public ResponseEntity<List<AutomaticDtos.AttemptResponse>> attempts(UUID id, UUID chargeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(automatic.attempts(CallerContext.current(), id, chargeId).stream().map(this::toResponse).toList());
    }

    @Override
    public ResponseEntity<AutomaticDtos.ChargeResponse> cancelCharge(UUID id, UUID chargeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(automatic.cancelCharge(CallerContext.current(), id, chargeId)));
    }

    private ResponseEntity<AutomaticDtos.AuthorizationResponse> ok(AutomaticAuthorization authorization) {
        return ResponseEntity.ok().eTag(String.valueOf(authorization.version())).cacheControl(CacheControl.noStore()).body(toResponse(authorization));
    }

    private AutomaticDtos.AuthorizationResponse toResponse(AutomaticAuthorization authorization) {
        return new AutomaticDtos.AuthorizationResponse(authorization.id(), authorization.idRec(), authorization.allowRetry(), authorization.payerAccountId(),
                Document.of(authorization.receiverDocument()).masked(), authorization.receiverName(),
                authorization.contract(), authorization.periodicity(), authorization.startDate(), authorization.endDate(), Money.bcbOrNull(authorization.fixedAmount()),
                Money.bcbOrNull(authorization.receiverMinAmount()), Money.bcbOrNull(authorization.payerMaxAmount()), authorization.status(),
                authorization.requestStatus(), authorization.rejectionCode(), authorization.cancelledBy(), authorization.approvedAt(), authorization.requestExpiresAt(), authorization.version());
    }

    private AutomaticDtos.ChargeResponse toResponse(AutomaticCharge automaticCharge) {
        return new AutomaticDtos.ChargeResponse(automaticCharge.id(), automaticCharge.authorizationId(), automaticCharge.txid(), automaticCharge.dueDate(),
                automaticCharge.amount().toBcb(), automaticCharge.status());
    }

    private AutomaticDtos.AttemptResponse toResponse(AutomaticAttempt automaticAttempt) {
        return new AutomaticDtos.AttemptResponse(automaticAttempt.id(), automaticAttempt.type(), automaticAttempt.status(), automaticAttempt.endToEndId(),
                automaticAttempt.settlementDate(), automaticAttempt.paymentOrderId(), automaticAttempt.lastErrorCode());
    }
}
