package br.com.pixmanager.infrastructure.directory;

import br.com.pixmanager.core.port.out.directory.KeyDirectoryPortOut;
import br.com.pixmanager.core.port.in.pixkey.FindActiveKeyUseCase;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Ponte entre módulos: {@code charge} → {@code pixkey} SOMENTE pelo {@code application.port.in} do outro módulo. */
@Component
public class KeyDirectoryAdapter implements KeyDirectoryPortOut {

    private final FindActiveKeyUseCase keys;

    KeyDirectoryAdapter(FindActiveKeyUseCase keys) {
        this.keys = keys;
    }

    @Override
    public Optional<ActiveKey> findActiveKey(String rawKey) {
        return keys.findActive(rawKey).map(pixKey -> new ActiveKey(pixKey.keyId(), pixKey.accountId(), pixKey.holderDocumentHash()));
    }

    @Override
    public Optional<String> findKeyValue(UUID keyId) {
        return keys.findValueById(keyId);
    }
}
