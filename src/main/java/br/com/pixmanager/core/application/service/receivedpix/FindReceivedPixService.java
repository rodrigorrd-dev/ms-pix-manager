package br.com.pixmanager.core.application.service.receivedpix;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.port.in.receivedpix.FindReceivedPixUseCase;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;

import java.util.Optional;

/** Pix de conta fora do escopo do chamador é indistinguível de inexistente (404). */
public class FindReceivedPixService implements FindReceivedPixUseCase {

    private final ReceivedPixPersistencePortOut persistence;

    public FindReceivedPixService(ReceivedPixPersistencePortOut persistence) {
        this.persistence = persistence;
    }

    @Override
    public Optional<ReceivedPix> findByEndToEndId(Caller caller, String endToEndId) {
        return persistence.findByEndToEndId(endToEndId).filter(receivedPix -> caller.canAccess(receivedPix.receiverAccountId()));
    }

    @Override
    public PageResult<ReceivedPix> search(Caller caller, ReceivedPixSearch search, PageQuery page) {
        return persistence.search(caller.accounts(), caller.allAccounts(), search, page);
    }
}
