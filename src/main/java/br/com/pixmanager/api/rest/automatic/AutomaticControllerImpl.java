package br.com.pixmanager.api.rest.automatic;

import br.com.pixmanager.api.rest.automatic.dto.AutomaticDtos;
import br.com.pixmanager.api.rest.security.CallerContext;
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
    public ResponseEntity<AutomaticDtos.AuthorizationResponse> receive(AutomaticDtos.ReceiveAuthorization r) {
        AutomaticAuthorization a = automatic.receiveAuthorization(CallerContext.current(), new ReceiveAuthorizationCommand(r.idRec(), r.payerAccountId(), r.payerDocument(),
                r.receiverDocument(), r.receiverName(), r.contract(), r.objectDescription(), r.periodicity(), r.startDate(), r.endDate(), money(r.fixedAmount()),
                money(r.receiverMinAmount())));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .location(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(a.id()).toUri()).body(toResponse(a));
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
        return ok(automatic.approve(CallerContext.current(), id, body == null ? null : money(body.payerMaxAmount())));
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
        return ok(automatic.adjustLimit(CallerContext.current(), id, money(body.payerMaxAmount())));
    }

    @Override
    public ResponseEntity<AutomaticDtos.ChargeResponse> registerCharge(UUID id, AutomaticDtos.RegisterCharge r) {
        AutomaticCharge c = automatic.registerCharge(CallerContext.current(), id, new RegisterChargeCommand(r.txid(), r.dueDate(), money(r.amount()), r.receiverIspb(),
                r.receiverBranch(), r.receiverAccount(), r.receiverAccountType()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(toResponse(c));
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

    private ResponseEntity<AutomaticDtos.AuthorizationResponse> ok(AutomaticAuthorization a) {
        return ResponseEntity.ok().eTag(String.valueOf(a.version())).cacheControl(CacheControl.noStore()).body(toResponse(a));
    }

    private static Money money(String v) {
        return v == null ? null : Money.parseBcb(v);
    }

    private AutomaticDtos.AuthorizationResponse toResponse(AutomaticAuthorization a) {
        return new AutomaticDtos.AuthorizationResponse(a.id(), a.idRec(), a.allowRetry(), a.payerAccountId(), Document.of(a.receiverDocument()).masked(), a.receiverName(),
                a.contract(), a.periodicity(), a.startDate(), a.endDate(), a.fixedAmount() == null ? null : a.fixedAmount().toBcb(),
                a.receiverMinAmount() == null ? null : a.receiverMinAmount().toBcb(), a.payerMaxAmount() == null ? null : a.payerMaxAmount().toBcb(), a.status(),
                a.requestStatus(), a.rejectionCode(), a.cancelledBy(), a.approvedAt(), a.requestExpiresAt(), a.version());
    }

    private AutomaticDtos.ChargeResponse toResponse(AutomaticCharge c) {
        return new AutomaticDtos.ChargeResponse(c.id(), c.authorizationId(), c.txid(), c.dueDate(), c.amount().toBcb(), c.status());
    }

    private AutomaticDtos.AttemptResponse toResponse(AutomaticAttempt t) {
        return new AutomaticDtos.AttemptResponse(t.id(), t.type(), t.status(), t.endToEndId(), t.settlementDate(), t.paymentOrderId(), t.lastErrorCode());
    }
}
