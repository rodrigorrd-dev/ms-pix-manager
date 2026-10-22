package br.com.pixmanager.core.application.service.receivedpix;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.ChargeSettlement;
import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.PixPayer;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.event.PixReceivedEvent;
import br.com.pixmanager.core.domain.enums.ReconciliationStatus;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.charge.SettleChargeUseCase;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase;
import br.com.pixmanager.core.port.in.webhook.NotifyPixReceivedUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.event.InboxPortOut;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.UUID;

/**
 * BR-PIX-01..04 — roda TODA numa transação (o use case é envolvido por TransactionalUseCases): inbox + Pix + conciliação
 * da cobrança + outbox são atômicos. O SPI já liquidou: divergência de valor/expiração NUNCA rejeita o Pix, só marca a
 * conciliação ({@link ReconciliationStatus}). Falha de NEGÓCIO (conta desconhecida) lança {@link NotFoundException}
 * ⇒ o consumidor manda direto à DLT, sem retry.
 */
public class ReceivePixService implements ReceivePixUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReceivePixService.class);

    private final InboxPortOut inbox;
    private final ReceivedPixPersistencePortOut persistence;
    private final FindAccountUseCase accounts;
    private final SettleChargeUseCase charges;
    private final DomainEventPublisherPortOut events;
    private final IdGeneratorPortOut ids;
    private final Clock clock;
    private final NotifyPixReceivedUseCase webhooks;

    public ReceivePixService(InboxPortOut inbox, ReceivedPixPersistencePortOut persistence, FindAccountUseCase accounts,
                             SettleChargeUseCase charges, DomainEventPublisherPortOut events, IdGeneratorPortOut ids, Clock clock) {
        this(inbox, persistence, accounts, charges, events, ids, clock, (pix, keyHash) -> { });
    }

    public ReceivePixService(InboxPortOut inbox, ReceivedPixPersistencePortOut persistence, FindAccountUseCase accounts,
                             SettleChargeUseCase charges, DomainEventPublisherPortOut events, IdGeneratorPortOut ids, Clock clock,
                             NotifyPixReceivedUseCase webhooks) {
        this.inbox = inbox;
        this.persistence = persistence;
        this.accounts = accounts;
        this.charges = charges;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.webhooks = webhooks;
    }

    @Override
    public ReceivePixResult receive(ReceivePixCommand cmd) {
        if (!inbox.firstTime(cmd.consumerGroup(), cmd.messageId(), cmd.topic(), cmd.partition(), cmd.offset())) {
            return new ReceivePixResult.Duplicate();
        }
        EndToEndId e2e = new EndToEndId(cmd.endToEndId());
        if (persistence.findByEndToEndId(e2e.value()).isPresent()) {
            return new ReceivePixResult.Duplicate();          // outra mensagem, mesmo Pix (BR-PIX-01)
        }
        AccountInfo receiver = accounts.findByIdentity(cmd.receiverIspb(), cmd.receiverBranch(), cmd.receiverAccount(),
                        cmd.receiverAccountType())
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Conta recebedora não encontrada para o Pix."));

        UUID id = ids.newId();
        ReceivedPix pix = ReceivedPix.receive(id, e2e, cmd.txid(), receiver.id(), cmd.amount(), payerOf(cmd), cmd.payerInfo(),
                cmd.settledAt(), cmd.messageId(), cmd.components(), clock.instant());
        if (!pix.componentsMatchAmount()) {
            log.warn("Composição do valor diverge do total do Pix {}; crédito mantido (alerta de conciliação)", e2e);
        }
        ChargeSettlement settlement = cmd.txid() == null ? new ChargeSettlement.NotFound()
                : charges.settle(receiver.id(), cmd.txid(), id, cmd.amount(), cmd.settledAt());
        ReceivedPix reconciled = switch (settlement) {
            case ChargeSettlement.Settled settled -> pix.reconciled(ReconciliationStatus.MATCHED, settled.chargeId());
            case ChargeSettlement.PaidAfterExpiration paidAfterExpiration -> pix.reconciled(ReconciliationStatus.MATCHED_AFTER_EXPIRATION, paidAfterExpiration.chargeId());
            case ChargeSettlement.AmountMismatch amountMismatch -> pix.reconciled(ReconciliationStatus.MISMATCH, amountMismatch.chargeId());
            case ChargeSettlement.NotPayable notPayable -> pix.reconciled(ReconciliationStatus.NO_CHARGE, null);
            case ChargeSettlement.NotFound notFound -> pix.reconciled(ReconciliationStatus.NO_CHARGE, null);
        };
        ReceivedPix saved = persistence.save(reconciled);
        events.publish(new PixReceivedEvent(saved));
        webhooks.pixReceived(saved, cmd.receiverKeyHash());          // notificação ao recebedor: na mesma transação, e nunca derruba o crédito
        return new ReceivePixResult.Received(saved);
    }

    /** Documento do pagador inválido/mascarado não derruba o Pix: guarda sem documento (o SPI já liquidou). */
    private static PixPayer payerOf(ReceivePixCommand cmd) {
        Document document = cmd.payerDocument() == null ? null : Document.tryParse(cmd.payerDocument()).orElse(null);
        return new PixPayer(document, cmd.payerName(), cmd.payerIspb(), cmd.payerBranch(), cmd.payerAccount(), cmd.payerAccountType());
    }
}
