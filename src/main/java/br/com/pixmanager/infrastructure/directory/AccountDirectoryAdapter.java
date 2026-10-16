package br.com.pixmanager.infrastructure.directory;

import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.port.out.directory.AccountDirectoryPortOut;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class AccountDirectoryAdapter implements AccountDirectoryPortOut {

    private final FindAccountUseCase accounts;

    AccountDirectoryAdapter(FindAccountUseCase accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<String> holderDocumentHash(UUID accountId) {
        return accounts.findById(accountId).map(AccountInfo::documentHash);
    }
}
