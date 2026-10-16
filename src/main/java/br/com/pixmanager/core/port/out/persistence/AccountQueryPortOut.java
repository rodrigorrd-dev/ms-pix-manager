package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.AccountInfo;

import java.util.Optional;
import java.util.UUID;

public interface AccountQueryPortOut {

    Optional<AccountInfo> findById(UUID accountId);

    Optional<AccountInfo> findByIdentity(String ispb, String branch, String number, String accountType);
}
