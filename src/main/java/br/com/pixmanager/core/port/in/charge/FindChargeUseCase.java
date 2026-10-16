package br.com.pixmanager.core.port.in.charge;

import br.com.pixmanager.core.domain.enums.ChargeStatus;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.core.domain.entity.Caller;

import java.time.Instant;
import java.util.Optional;

public interface FindChargeUseCase {

    Optional<ChargeDetails> findByTxid(Caller caller, TxId txid);

    PageResult<ChargeDetails> search(Caller caller, ChargeSearch search, PageQuery page);

    /** Filtros do {@code GET /cob}: período obrigatório (BCB), status, devedor, presença de location. */
    record ChargeSearch(Instant from, Instant to, Optional<ChargeStatus> status, Optional<Document> debtor,
                        Optional<Boolean> locationPresent) {

        public ChargeSearch {
            if (from == null || to == null || from.isAfter(to)) {
                throw new IllegalArgumentException("intervalo inválido: inicio deve ser <= fim");
            }
        }
    }
}
