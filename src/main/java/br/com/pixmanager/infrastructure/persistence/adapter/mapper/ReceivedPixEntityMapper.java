package br.com.pixmanager.infrastructure.persistence.adapter.mapper;

import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PixPayer;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.ValueComponent;
import br.com.pixmanager.core.domain.enums.ReconciliationStatus;
import br.com.pixmanager.infrastructure.persistence.entity.ReceivedPixEntity;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import br.com.pixmanager.shared.model.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/** Domínio ⇄ entidade; o documento do pagador é cifrado (AES-GCM) + índice cego (HMAC) só aqui. */
@Component
public class ReceivedPixEntityMapper {

    private final CryptoService crypto;

    public ReceivedPixEntityMapper(CryptoService crypto) {
        this.crypto = crypto;
    }

    public String hashOf(Document document) {
        return crypto.blindIndex(document.value());
    }

    public ReceivedPix toDomain(ReceivedPixEntity entity, List<ValueComponent> components) {
        Document doc = entity.getPayerDocumentEnc() == null ? null : Document.of(crypto.decrypt(entity.getPayerDocumentEnc()));
        PixPayer payer = new PixPayer(doc, entity.getPayerName(), entity.getPayerIspb(), entity.getPayerBranch(), entity.getPayerAccount(),
                entity.getPayerAccountType());
        return new ReceivedPix(entity.getId(), new EndToEndId(entity.getEndToEndId()), entity.getTxid(), entity.getChargeId(), entity.getReceiverAccountId(),
                Money.of(entity.getAmount()), Money.of(entity.getRefundableAmount()), Money.of(entity.getRefundedTotal()), payer, entity.getPayerInfo(),
                entity.getSettledAt(), ReconciliationStatus.valueOf(entity.getReconciliationStatus()), entity.getSourceMessageId(), components,
                entity.getVersion(), entity.getCreatedAt());
    }

    public void apply(ReceivedPix receivedPix, ReceivedPixEntity entity) {
        entity.setEndToEndId(receivedPix.endToEndId().value());
        entity.setTxid(receivedPix.txid());
        entity.setChargeId(receivedPix.chargeId());
        entity.setReceiverAccountId(receivedPix.receiverAccountId());
        entity.setAmount(receivedPix.amount().value());
        entity.setRefundableAmount(receivedPix.refundableAmount().value());
        entity.setRefundedTotal(receivedPix.refundedTotal().value());
        PixPayer pixPayer = receivedPix.payer();
        if (pixPayer.document() != null) {
            entity.setPayerType(pixPayer.document().type());
            entity.setPayerDocumentEnc(crypto.encrypt(pixPayer.document().value()));
            entity.setPayerDocumentHash(hashOf(pixPayer.document()));
            entity.setPayerDocumentMasked(pixPayer.document().masked());
        }
        entity.setPayerName(pixPayer.name());
        entity.setPayerIspb(pixPayer.ispb());
        entity.setPayerBranch(pixPayer.branch());
        entity.setPayerAccount(pixPayer.account());
        entity.setPayerAccountType(pixPayer.accountType());
        entity.setPayerInfo(receivedPix.payerInfo());
        entity.setSettledAt(receivedPix.settledAt());
        entity.setReconciliationStatus(receivedPix.reconciliationStatus().name());
        entity.setSourceMessageId(receivedPix.sourceMessageId());
    }
}
