package br.com.pixmanager.core.port.in.charge;

import br.com.pixmanager.core.domain.entity.Debtor;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.shared.model.ExpectedVersion;

public interface ReviseChargeUseCase {

    ChargeDetails revise(Caller caller, TxId txid, ExpectedVersion expected, ReviseChargeCommand command);

    /** {@code remove=true} ⇒ REMOVIDA_PELO_USUARIO_RECEBEDOR; senão altera só o que veio preenchido (revisão). */
    record ReviseChargeCommand(boolean remove, Money amount, Debtor debtor, String payerRequest, Integer expirationSeconds) {}
}
