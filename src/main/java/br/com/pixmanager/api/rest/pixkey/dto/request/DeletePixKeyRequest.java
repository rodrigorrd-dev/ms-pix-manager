package br.com.pixmanager.api.rest.pixkey.dto.request;

import br.com.pixmanager.core.domain.enums.DeletionReason;
import jakarta.validation.constraints.NotNull;

public record DeletePixKeyRequest(@NotNull DeletionReason reason) {}
