package br.com.pixmanager.core.application.service.charge;

import br.com.pixmanager.core.port.in.charge.ChargeDetails;
import br.com.pixmanager.core.port.in.charge.FindChargeUseCase;
import br.com.pixmanager.core.port.out.persistence.ChargePersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.ChargeQueryPortOut;
import br.com.pixmanager.core.port.out.directory.KeyDirectoryPortOut;
import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.shared.exception.ConflictException;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.model.dto.PageQuery;
import br.com.pixmanager.shared.model.dto.PageResult;
import br.com.pixmanager.core.domain.entity.Caller;

import java.util.List;
import java.util.Optional;

/** Recurso fora das contas do chamador é INDISTINGUÍVEL de inexistente (404): não vaza existência. */
public class FindChargeService implements FindChargeUseCase {

    private final ChargePersistencePortOut persistence;
    private final ChargeQueryPortOut query;
    private final KeyDirectoryPortOut keys;

    public FindChargeService(ChargePersistencePortOut persistence, ChargeQueryPortOut query, KeyDirectoryPortOut keys) {
        this.persistence = persistence;
        this.query = query;
        this.keys = keys;
    }

    @Override
    public Optional<ChargeDetails> findByTxid(Caller caller, TxId txid) {
        return visible(caller, txid).map(this::details);
    }

    /** Pacote privado ao módulo (também usado pela revisão). */
    Optional<Charge> visible(Caller caller, TxId txid) {
        List<Charge> mine = persistence.findByTxid(txid).stream().filter(c -> caller.canAccess(c.receiverAccountId())).toList();
        if (mine.size() > 1) {
            throw new ConflictException(ErrorCodes.CONFLICT, "txid ambíguo entre contas do chamador; informe a conta.");
        }
        return mine.stream().findFirst();
    }

    @Override
    public PageResult<ChargeDetails> search(Caller caller, ChargeSearch search, PageQuery page) {
        return query.search(caller.accounts(), caller.allAccounts(), search, page).map(this::details);
    }

    ChargeDetails details(Charge charge) {
        return new ChargeDetails(charge, keys.findKeyValue(charge.pixKeyId()).orElse(null));
    }
}
