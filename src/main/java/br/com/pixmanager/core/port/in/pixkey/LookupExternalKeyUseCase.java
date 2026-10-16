package br.com.pixmanager.core.port.in.pixkey;

import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut.DictEntry;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.entity.Caller;

import java.util.Optional;
import java.util.UUID;

public interface LookupExternalKeyUseCase {

    /** {@code payerAccountId}: conta do solicitante (o titular é o "requester" auditado pelo DICT). */
    Optional<DictEntry> lookup(Caller caller, UUID payerAccountId, PixKeyType type, String rawValue);
}
