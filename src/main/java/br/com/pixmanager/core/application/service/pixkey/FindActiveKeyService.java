package br.com.pixmanager.core.application.service.pixkey;

import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.pixkey.ActiveKeyView;
import br.com.pixmanager.core.port.in.pixkey.FindActiveKeyUseCase;
import br.com.pixmanager.core.port.out.persistence.PixKeyPersistencePortOut;
import br.com.pixmanager.core.domain.entity.PixKeyValue;

import java.util.Optional;
import java.util.UUID;

public class FindActiveKeyService implements FindActiveKeyUseCase {

    private final PixKeyPersistencePortOut persistence;
    private final FindAccountUseCase accounts;

    public FindActiveKeyService(PixKeyPersistencePortOut persistence, FindAccountUseCase accounts) {
        this.persistence = persistence;
        this.accounts = accounts;
    }

    @Override
    public Optional<ActiveKeyView> findActive(String rawKey) {
        return PixKeyValue.infer(rawKey)
                .flatMap(persistence::findLiveByValue)
                .filter(k -> k.isActive())
                .flatMap(key -> accounts.findById(key.accountId())
                        .map(account -> new ActiveKeyView(key.id(), account.id(), account.documentHash(), key.value().masked())));
    }

    @Override
    public Optional<String> findValueById(UUID keyId) {
        return persistence.findById(keyId).map(k -> k.value().value());
    }
}
