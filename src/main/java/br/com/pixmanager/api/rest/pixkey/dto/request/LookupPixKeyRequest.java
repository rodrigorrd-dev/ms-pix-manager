package br.com.pixmanager.api.rest.pixkey.dto.request;

import br.com.pixmanager.core.domain.enums.PixKeyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record LookupPixKeyRequest(
        @NotNull UUID payerAccountId,
        @NotNull PixKeyType pixKeyType,
        @NotBlank @Size(max = 77) String pixKey) {}
