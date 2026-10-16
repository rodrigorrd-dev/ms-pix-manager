package br.com.pixmanager.core.port.in.account;

import br.com.pixmanager.core.domain.entity.AccountInfo;

import java.util.Optional;
import java.util.UUID;

public interface FindAccountUseCase {

    Optional<AccountInfo> findById(UUID accountId);

    /** Conta pela identidade bancária (ISPB/agência/número/tipo ISO 20022) — como chega do SPI. */
    Optional<AccountInfo> findByIdentity(String ispb, String branch, String number, String accountType);
}
