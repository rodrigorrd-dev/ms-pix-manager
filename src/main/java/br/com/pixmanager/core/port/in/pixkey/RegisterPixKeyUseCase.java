package br.com.pixmanager.core.port.in.pixkey;

import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.entity.Caller;

import java.util.UUID;

public interface RegisterPixKeyUseCase {

    /** Registra localmente (PENDING_*); a ativação no DICT ocorre fora da transação, pelo job. */
    PixKey register(Caller caller, UUID accountId, PixKeyType type, String rawValue);
}
