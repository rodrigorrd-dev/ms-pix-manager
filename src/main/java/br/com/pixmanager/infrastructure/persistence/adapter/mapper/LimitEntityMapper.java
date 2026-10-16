package br.com.pixmanager.infrastructure.persistence.adapter.mapper;

import br.com.pixmanager.core.domain.entity.AccountLimit;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.infrastructure.persistence.entity.LimitEntity;
import org.springframework.stereotype.Component;

@Component
public class LimitEntityMapper {

    public AccountLimit toDomain(LimitEntity e) {
        return new AccountLimit(e.getId(), e.getAccountId(), LimitScope.valueOf(e.getScope()), Money.of(e.getAmount()),
                e.getCeilingAmount() == null ? null : Money.of(e.getCeilingAmount()),
                e.getPendingAmount() == null ? null : Money.of(e.getPendingAmount()), e.getPendingRequestedAt(),
                e.getPendingEffectiveAt(), e.getVersion());
    }

    public void apply(AccountLimit d, LimitEntity e, boolean isNew) {
        if (isNew) {
            e.setAccountId(d.accountId());
            e.setScope(d.scope().name());
        }
        e.setAmount(d.amount().value());
        e.setCeilingAmount(d.ceiling() == null ? null : d.ceiling().value());
        e.setPendingAmount(d.pendingAmount() == null ? null : d.pendingAmount().value());
        e.setPendingRequestedAt(d.pendingRequestedAt());
        e.setPendingEffectiveAt(d.pendingEffectiveAt());
    }
}
