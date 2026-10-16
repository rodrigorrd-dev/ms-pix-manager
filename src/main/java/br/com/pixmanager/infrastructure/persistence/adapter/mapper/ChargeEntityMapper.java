package br.com.pixmanager.infrastructure.persistence.adapter.mapper;

import br.com.pixmanager.infrastructure.persistence.entity.ChargeEntity;

import br.com.pixmanager.core.domain.entity.AdditionalInfo;
import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.core.domain.enums.ChargeStatus;
import br.com.pixmanager.core.domain.enums.ChargeType;
import br.com.pixmanager.core.domain.entity.Debtor;
import br.com.pixmanager.core.domain.entity.PixLocation;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.shared.model.document.Document;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Domínio ⇄ entidade. O documento do devedor é cifrado/indexado só aqui (AES-GCM + HMAC). */
@Component
public class ChargeEntityMapper {

    private final CryptoService crypto;

    public ChargeEntityMapper(CryptoService crypto) {
        this.crypto = crypto;
    }

    public String hashOf(Document document) {
        return crypto.blindIndex(document.value());
    }

    public Charge toDomain(ChargeEntity e, List<AdditionalInfo> info, PixLocation location) {
        Debtor debtor = e.getDebtorDocumentEnc() == null ? null
                : new Debtor(Document.of(crypto.decrypt(e.getDebtorDocumentEnc())), e.getDebtorName());
        return new Charge(e.getId(), ChargeType.valueOf(e.getChargeType()), new TxId(e.getTxid()), e.getReceiverAccountId(),
                e.getReceiverDocumentHash(), e.getPixKeyId(), ChargeStatus.valueOf(e.getStatus()), Money.of(e.getOriginalAmount()),
                e.getExpirationSeconds(), e.getExpiresAt(), e.getDueDate(), e.getValidityAfterDueDays(), debtor,
                e.getPayerRequest(), info, location, e.getRevision(), e.getPaidReceivedId(), e.getRemovedAt(),
                e.getVersion(), e.getCreatedAt());
    }

    public void apply(Charge d, ChargeEntity e, boolean isNew) {
        if (isNew) {
            e.setChargeType(d.type().name());
            e.setTxid(d.txid().value());
            e.setReceiverAccountId(d.receiverAccountId());
            e.setReceiverDocumentHash(d.receiverDocumentHash());
            e.setPixKeyId(d.pixKeyId());
            e.setDueDate(d.dueDate());
            e.setValidityAfterDueDays(d.validityAfterDueDays());
        }
        e.setStatus(d.status().name());
        e.setOriginalAmount(d.originalAmount().value());
        e.setExpirationSeconds(d.expirationSeconds());
        e.setExpiresAt(d.expiresAt());
        if (d.debtor() == null) {
            e.setDebtorType(null);
            e.setDebtorDocumentEnc(null);
            e.setDebtorDocumentHash(null);
            e.setDebtorName(null);
        } else {
            e.setDebtorType(d.debtor().document().type());
            e.setDebtorDocumentEnc(crypto.encrypt(d.debtor().document().value()));
            e.setDebtorDocumentHash(hashOf(d.debtor().document()));
            e.setDebtorName(d.debtor().name());
        }
        e.setPayerRequest(d.payerRequest());
        e.setLocationId(d.location() == null ? null : d.location().id());
        e.setRevision(d.revision());
        e.setPaidReceivedId(d.paidReceivedId());
        e.setRemovedAt(d.removedAt());
    }
}
