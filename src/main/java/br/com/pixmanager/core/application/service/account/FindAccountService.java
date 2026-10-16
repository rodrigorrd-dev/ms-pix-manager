package br.com.pixmanager.core.application.service.account;

import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.out.persistence.AccountQueryPortOut;
import br.com.pixmanager.core.domain.entity.AccountInfo;

import java.util.Optional;
import java.util.UUID;

public class FindAccountService implements FindAccountUseCase {

    private final AccountQueryPortOut query;

    public FindAccountService(AccountQueryPortOut query) {
        this.query = query;
    }

    @Override
    public Optional<AccountInfo> findById(UUID accountId) {
        return query.findById(accountId);
    }

    @Override
    public Optional<AccountInfo> findByIdentity(String ispb, String branch, String number, String accountType) {
        return query.findByIdentity(ispb, branch, number, accountType);
    }
}
