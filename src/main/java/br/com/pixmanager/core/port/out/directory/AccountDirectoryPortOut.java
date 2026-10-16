package br.com.pixmanager.core.port.out.directory;

import java.util.Optional;
import java.util.UUID;

public interface AccountDirectoryPortOut {

    /** Índice cego do documento do titular da conta (escopo de unicidade do txid). */
    Optional<String> holderDocumentHash(UUID accountId);
}
