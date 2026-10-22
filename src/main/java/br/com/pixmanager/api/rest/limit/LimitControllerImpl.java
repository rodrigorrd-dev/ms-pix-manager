package br.com.pixmanager.api.rest.limit;

import br.com.pixmanager.api.rest.limit.dto.request.ChangeLimitRequest;
import br.com.pixmanager.api.rest.limit.dto.response.LimitResponse;
import br.com.pixmanager.api.rest.limit.dto.response.LimitsResponse;
import br.com.pixmanager.api.rest.security.CallerContext;
import br.com.pixmanager.core.domain.entity.AccountLimit;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.core.port.in.limit.ManageLimitUseCase;
import br.com.pixmanager.shared.model.ExpectedVersion;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@RestController
public class LimitControllerImpl implements LimitController {

    private final ManageLimitUseCase manage;
    private final Clock clock;

    public LimitControllerImpl(ManageLimitUseCase manage, Clock clock) {
        this.manage = manage;
        this.clock = clock;
    }

    @Override
    public ResponseEntity<LimitsResponse> list(UUID accountId) {
        var views = manage.list(CallerContext.current(), accountId).stream()
                .map(limitView -> new LimitResponse(limitView.scope(), limitView.effective().toBcb(), limitView.ceiling() == null ? null : limitView.ceiling().toBcb(),
                        limitView.pendingAmount() == null ? null : limitView.pendingAmount().toBcb(), limitView.pendingEffectiveAt(), limitView.isDefault()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LimitsResponse(accountId, views));
    }

    @Override
    public ResponseEntity<LimitResponse> change(UUID accountId, LimitScope scope, String ifMatch, ChangeLimitRequest request) {
        Optional<ExpectedVersion> expected = ifMatch == null || ifMatch.isBlank() ? Optional.empty() : Optional.of(ExpectedVersion.parse(ifMatch));
        AccountLimit saved = manage.change(CallerContext.current(), accountId, scope, Money.parseBcb(request.amount()), expected);
        var now = clock.instant();
        return ResponseEntity.ok().eTag(String.valueOf(saved.version())).cacheControl(CacheControl.noStore())
                .body(new LimitResponse(saved.scope(), saved.effective(now).toBcb(), saved.ceiling() == null ? null : saved.ceiling().toBcb(),
                        saved.pendingAmount() == null ? null : saved.pendingAmount().toBcb(), saved.pendingEffectiveAt(), false));
    }
}
