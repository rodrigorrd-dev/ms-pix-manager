package br.com.pixmanager.core.application.service.pixkey;

import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.pixkey.LookupExternalKeyUseCase;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut.DictEntry;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.core.domain.entity.Caller;

import java.util.Optional;
import java.util.UUID;

public class LookupExternalKeyService implements LookupExternalKeyUseCase {

    private final DictGatewayPortOut dict;
    private final FindAccountUseCase accounts;

    public LookupExternalKeyService(DictGatewayPortOut dict, FindAccountUseCase accounts) {
        this.dict = dict;
        this.accounts = accounts;
    }

    @Override
    public Optional<DictEntry> lookup(Caller caller, UUID payerAccountId, PixKeyType type, String rawValue) {
        if (!caller.canAccess(payerAccountId)) {
            throw new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada.");
        }
        var payer = accounts.findById(payerAccountId)
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada."));
        PixKeyValue value = PixKeyValue.of(type, rawValue);           // valida o formato antes de gastar consulta do DICT
        return dict.lookup(value, payer.document());
    }
}
