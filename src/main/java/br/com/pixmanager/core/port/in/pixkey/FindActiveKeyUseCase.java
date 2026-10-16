package br.com.pixmanager.core.port.in.pixkey;

import java.util.Optional;
import java.util.UUID;

public interface FindActiveKeyUseCase {

    /** {@code rawKey} sem tipo (como no BCB): o tipo é inferido pelo formato. Vazio se inexistente/inativa/inválida. */
    Optional<ActiveKeyView> findActive(String rawKey);

    /** Valor (decifrado) da chave pelo id — para montar a resposta {@code chave} da cobrança do PRÓPRIO recebedor. */
    Optional<String> findValueById(UUID keyId);
}
