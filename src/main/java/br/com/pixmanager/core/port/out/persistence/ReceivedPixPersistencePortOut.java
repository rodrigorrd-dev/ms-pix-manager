package br.com.pixmanager.core.port.out.persistence;

import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.port.in.receivedpix.FindReceivedPixUseCase.ReceivedPixSearch;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ReceivedPixPersistencePortOut {

    ReceivedPix save(ReceivedPix pix);

    Optional<ReceivedPix> findByEndToEndId(String endToEndId);

    /**
     * Reserva valor devolvível de forma ATÔMICA: {@code UPDATE … SET refunded_total = refunded_total + :v WHERE refunded_total + :v
     * <= refundable_amount}. Devolve {@code false} se excederia o devolvível (BR-REF-03) — sem {@code read → decide → write}.
     */
    boolean reserveRefundable(UUID receivedId, Money amount);

    /** Devolve o valor reservado (devolução que não se realizou). */
    void releaseRefundable(UUID receivedId, Money amount);

    PageResult<ReceivedPix> search(Set<UUID> accounts, boolean allAccounts, ReceivedPixSearch search, PageQuery page);
}
