package br.com.pixmanager.core.port.in.limit;

import br.com.pixmanager.core.domain.entity.AccountLimit;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.shared.model.ExpectedVersion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManageLimitUseCase {

    /** Limites da conta: os configurados + os padrões (o que vale quando nada foi configurado). */
    List<LimitView> list(Caller caller, UUID accountId);

    /** Redução imediata; aumento só após a carência; acima do teto ⇒ 422. {@code expected} obrigatório se já existe configuração. */
    AccountLimit change(Caller caller, UUID accountId, LimitScope scope, Money amount, Optional<ExpectedVersion> expected);

    record LimitView(LimitScope scope, Money effective, Money ceiling, Money pendingAmount, Instant pendingEffectiveAt, Long version,
                     boolean isDefault) {}
}
