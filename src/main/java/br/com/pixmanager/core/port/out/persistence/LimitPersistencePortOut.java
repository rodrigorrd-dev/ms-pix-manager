package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.AccountLimit;
import br.com.pixmanager.core.domain.enums.LimitScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LimitPersistencePortOut {

    List<AccountLimit> findByAccount(UUID accountId);

    Optional<AccountLimit> find(UUID accountId, LimitScope scope);

    AccountLimit save(AccountLimit limit);
}
