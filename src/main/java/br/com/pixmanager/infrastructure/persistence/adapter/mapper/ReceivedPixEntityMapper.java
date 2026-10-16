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

    public ReceivedPix toDomain(ReceivedPixEntity e, List<ValueComponent> components) {
        Document doc = e.getPayerDocumentEnc() == null ? null : Document.of(crypto.decrypt(e.getPayerDocumentEnc()));
        PixPayer payer = new PixPayer(doc, e.getPayerName(), e.getPayerIspb(), e.getPayerBranch(), e.getPayerAccount(),
                e.getPayerAccountType());
        return new ReceivedPix(e.getId(), new EndToEndId(e.getEndToEndId()), e.getTxid(), e.getChargeId(), e.getReceiverAccountId(),
                Money.of(e.getAmount()), Money.of(e.getRefundableAmount()), Money.of(e.getRefundedTotal()), payer, e.getPayerInfo(),
                e.getSettledAt(), ReconciliationStatus.valueOf(e.getReconciliationStatus()), e.getSourceMessageId(), components,
                e.getVersion(), e.getCreatedAt());
    }

    public void apply(ReceivedPix d, ReceivedPixEntity e) {
        e.setEndToEndId(d.endToEndId().value());
        e.setTxid(d.txid());
        e.setChargeId(d.chargeId());
        e.setReceiverAccountId(d.receiverAccountId());
        e.setAmount(d.amount().value());
        e.setRefundableAmount(d.refundableAmount().value());
        e.setRefundedTotal(d.refundedTotal().value());
        PixPayer p = d.payer();
        if (p.document() != null) {
            e.setPayerType(p.document().type());
            e.setPayerDocumentEnc(crypto.encrypt(p.document().value()));
            e.setPayerDocumentHash(hashOf(p.document()));
            e.setPayerDocumentMasked(p.document().masked());
        }
        e.setPayerName(p.name());
        e.setPayerIspb(p.ispb());
        e.setPayerBranch(p.branch());
        e.setPayerAccount(p.account());
        e.setPayerAccountType(p.accountType());
        e.setPayerInfo(d.payerInfo());
        e.setSettledAt(d.settledAt());
        e.setReconciliationStatus(d.reconciliationStatus().name());
        e.setSourceMessageId(d.sourceMessageId());
    }
}
