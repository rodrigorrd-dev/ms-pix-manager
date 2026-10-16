package br.com.pixmanager.api.rest.pixkey.dto.response;

import java.time.Instant;

/** {@code ownerDocument}: mascarado por padrão; completo só com o escopo {@code pii.document.read} (decisão B8). */
public record PixKeyLookupResponse(String ownerName, String ownerDocument, String ispb, String branch, String accountType,
                                   Instant keyOwnershipDate) {}
