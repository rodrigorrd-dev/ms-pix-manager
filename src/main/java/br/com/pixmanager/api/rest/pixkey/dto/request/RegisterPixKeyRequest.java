package br.com.pixmanager.api.rest.pixkey.dto.request;

import br.com.pixmanager.core.domain.enums.PixKeyType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** {@code pixKey} é opcional só para EVP (gerada). CPF/CNPJ/PHONE/EMAIL são validados no domínio ({@code PixKeyValue}). */
public record RegisterPixKeyRequest(
        @NotNull UUID accountId,
        @NotNull PixKeyType pixKeyType,
        @Size(max = 77) String pixKey) {}
