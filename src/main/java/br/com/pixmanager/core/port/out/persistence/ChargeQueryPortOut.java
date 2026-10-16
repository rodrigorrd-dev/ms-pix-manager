package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.port.in.charge.FindChargeUseCase.ChargeSearch;
import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.util.Set;
import java.util.UUID;

/** Lado de leitura (CQRS-lite): filtros compostos, paginação obrigatória, sempre restrito às contas do chamador. */
public interface ChargeQueryPortOut {

    PageResult<Charge> search(Set<UUID> accounts, boolean allAccounts, ChargeSearch search, PageQuery page);
}
