package br.com.pixmanager.core.port.in.charge;

import br.com.pixmanager.core.domain.entity.ChargeSettlement;
import br.com.pixmanager.core.domain.entity.Money;

import java.time.Instant;
import java.util.UUID;

/** Porta usada pelo módulo {@code receivedpix} para conciliar um Pix recebido com a cobrança, na MESMA transação. */
public interface SettleChargeUseCase {

    ChargeSettlement settle(UUID receiverAccountId, String txid, UUID receivedPixId, Money paidAmount, Instant paidAt);
}
