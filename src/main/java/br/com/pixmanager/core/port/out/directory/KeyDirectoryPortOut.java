package br.com.pixmanager.core.port.out.directory;

import java.util.Optional;
import java.util.UUID;

/** Anticorrupção: o módulo {@code charge} enxerga chaves só por esta porta (a implementação chama {@code pixkey}). */
public interface KeyDirectoryPortOut {

    Optional<ActiveKey> findActiveKey(String rawKey);

    Optional<String> findKeyValue(UUID keyId);

    record ActiveKey(UUID keyId, UUID accountId, String holderDocumentHash) {}
}
