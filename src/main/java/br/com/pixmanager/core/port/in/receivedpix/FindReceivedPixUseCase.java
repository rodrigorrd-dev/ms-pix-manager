package br.com.pixmanager.core.port.in.receivedpix;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.time.Instant;
import java.util.Optional;

public interface FindReceivedPixUseCase {

    Optional<ReceivedPix> findByEndToEndId(Caller caller, String endToEndId);

    PageResult<ReceivedPix> search(Caller caller, ReceivedPixSearch search, PageQuery page);

    record ReceivedPixSearch(Instant from, Instant to, Optional<String> txid, Optional<Document> payer) {

        public ReceivedPixSearch {
            if (from == null || to == null || from.isAfter(to)) {
                throw new IllegalArgumentException("intervalo inválido: inicio deve ser <= fim");
            }
        }
    }
}
