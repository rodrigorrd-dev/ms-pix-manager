package br.com.pixmanager.core.application.service.charge;

import br.com.pixmanager.core.port.in.charge.SettleChargeUseCase;
import br.com.pixmanager.core.port.out.directory.AccountDirectoryPortOut;
import br.com.pixmanager.core.port.out.persistence.ChargePersistencePortOut;
import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.core.domain.entity.event.ChargeEvent;
import br.com.pixmanager.core.domain.entity.ChargeSettlement;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * BR-PIX-02/03 e BR-COB-10: concilia o Pix recebido com a cobrança. NUNCA lança por divergência — o SPI já liquidou; o
 * resultado selado diz ao módulo {@code receivedpix} qual {@code reconciliation_status} gravar.
 */
public class SettleChargeService implements SettleChargeUseCase {

    private final ChargePersistencePortOut persistence;
    private final AccountDirectoryPortOut accounts;
    private final DomainEventPublisherPortOut events;

    public SettleChargeService(ChargePersistencePortOut persistence, AccountDirectoryPortOut accounts,
                               DomainEventPublisherPortOut events) {
        this.persistence = persistence;
        this.accounts = accounts;
        this.events = events;
    }

    @Override
    public ChargeSettlement settle(UUID receiverAccountId, String txid, UUID receivedPixId, Money paidAmount, Instant paidAt) {
        if (!TxId.isValid(txid)) {
            return new ChargeSettlement.NotFound();               // txid de Pix sem cobrança pode ter {1,35}: não é erro
        }
        Optional<Charge> found = accounts.holderDocumentHash(receiverAccountId)
                .flatMap(hash -> persistence.findByReceiverHashAndTxid(hash, new TxId(txid)));
        if (found.isEmpty()) {
            return new ChargeSettlement.NotFound();
        }
        Charge charge = found.get();
        if (!charge.isActive()) {
            return new ChargeSettlement.NotPayable(charge.id());
        }
        if (charge.isExpiredAt(paidAt)) {
            return new ChargeSettlement.PaidAfterExpiration(charge.id());
        }
        if (!charge.originalAmount().equals(paidAmount)) {
            return new ChargeSettlement.AmountMismatch(charge.id());
        }
        Charge paid = persistence.save(charge.markPaid(receivedPixId));
        events.publish(new ChargeEvent("ChargePaid", paid));
        return new ChargeSettlement.Settled(paid.id());
    }
}
