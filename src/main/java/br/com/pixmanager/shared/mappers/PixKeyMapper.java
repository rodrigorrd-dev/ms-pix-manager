package br.com.pixmanager.shared.mappers;

import br.com.pixmanager.api.rest.pixkey.dto.response.PixKeyLookupResponse;
import br.com.pixmanager.api.rest.pixkey.dto.response.PixKeyResponse;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut.DictEntry;
import br.com.pixmanager.core.domain.entity.PixKey;

/** Borda REST: domínio → DTO. Aqui (e só aqui) se decide mascarar ou não o documento (BR-DOC-03 / B8). */
public final class PixKeyMapper {

    private PixKeyMapper() {}

    public static PixKeyResponse toResponse(PixKey key) {
        return new PixKeyResponse(key.id(), key.accountId(), key.value().type(), key.value().masked(), key.status(), key.createdAt());
    }

    public static PixKeyLookupResponse toResponse(DictEntry entry, boolean fullDocument) {
        String document = fullDocument ? entry.ownerDocument().value() : entry.ownerDocument().masked();
        return new PixKeyLookupResponse(entry.ownerName(), document, entry.ispb(), entry.branch(), entry.accountType(),
                entry.ownershipDate());
    }
}
