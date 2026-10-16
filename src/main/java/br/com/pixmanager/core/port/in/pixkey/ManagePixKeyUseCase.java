package br.com.pixmanager.core.port.in.pixkey;

import br.com.pixmanager.core.domain.enums.DeletionReason;
import br.com.pixmanager.core.domain.entity.PixKey;
import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.shared.model.ExpectedVersion;

import java.util.Optional;
import java.util.UUID;

public interface ManagePixKeyUseCase {

    PixKey get(Caller caller, UUID keyId);

    PageResult<PixKey> list(Caller caller, UUID accountId, Optional<PixKeyStatus> status, PageQuery page);

    /** Exclui no DICT (via gateway, fora de TX) e depois localmente; {@code If-Match} obrigatório. */
    PixKey delete(Caller caller, UUID keyId, DeletionReason reason, ExpectedVersion expected);
}
