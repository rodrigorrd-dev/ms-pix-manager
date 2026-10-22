package br.com.pixmanager.infrastructure.persistence.adapter.mapper;

import br.com.pixmanager.core.domain.entity.AccountLimit;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.infrastructure.persistence.entity.LimitEntity;
import org.springframework.stereotype.Component;

@Component
public class LimitEntityMapper {

    public AccountLimit toDomain(LimitEntity entity) {
        return new AccountLimit(entity.getId(), entity.getAccountId(), LimitScope.valueOf(entity.getScope()), Money.of(entity.getAmount()),
                Money.ofNullable(entity.getCeilingAmount()),
                Money.ofNullable(entity.getPendingAmount()), entity.getPendingRequestedAt(),
                entity.getPendingEffectiveAt(), entity.getVersion());
    }

    public void apply(AccountLimit accountLimit, LimitEntity entity, boolean isNew) {
        if (isNew) {
            entity.setAccountId(accountLimit.accountId());
            entity.setScope(accountLimit.scope().name());
        }
        entity.setAmount(accountLimit.amount().value());
        entity.setCeilingAmount(Money.valueOrNull(accountLimit.ceiling()));
        entity.setPendingAmount(Money.valueOrNull(accountLimit.pendingAmount()));
        entity.setPendingRequestedAt(accountLimit.pendingRequestedAt());
        entity.setPendingEffectiveAt(accountLimit.pendingEffectiveAt());
    }
}
