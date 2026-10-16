package br.com.pixmanager.api.rest.pixkey.dto.response;

import br.com.pixmanager.core.domain.enums.PixKeyStatus;
import br.com.pixmanager.core.domain.enums.PixKeyType;

import java.time.Instant;
import java.util.UUID;

/** Sem o valor da chave em claro: {@code pixKeyMasked}. */
public record PixKeyResponse(UUID id, UUID accountId, PixKeyType pixKeyType, String pixKeyMasked, PixKeyStatus status,
                             Instant createdAt) {}
