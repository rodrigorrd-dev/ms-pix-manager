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

    public Charge toDomain(ChargeEntity entity, List<AdditionalInfo> info, PixLocation location) {
        Debtor debtor = entity.getDebtorDocumentEnc() == null ? null
                : new Debtor(Document.of(crypto.decrypt(entity.getDebtorDocumentEnc())), entity.getDebtorName());
        return new Charge(entity.getId(), ChargeType.valueOf(entity.getChargeType()), new TxId(entity.getTxid()), entity.getReceiverAccountId(),
                entity.getReceiverDocumentHash(), entity.getPixKeyId(), ChargeStatus.valueOf(entity.getStatus()), Money.of(entity.getOriginalAmount()),
                entity.getExpirationSeconds(), entity.getExpiresAt(), entity.getDueDate(), entity.getValidityAfterDueDays(), debtor,
                entity.getPayerRequest(), info, location, entity.getRevision(), entity.getPaidReceivedId(), entity.getRemovedAt(),
                entity.getVersion(), entity.getCreatedAt());
    }

    public void apply(Charge charge, ChargeEntity entity, boolean isNew) {
        if (isNew) {
            entity.setChargeType(charge.type().name());
            entity.setTxid(charge.txid().value());
            entity.setReceiverAccountId(charge.receiverAccountId());
            entity.setReceiverDocumentHash(charge.receiverDocumentHash());
            entity.setPixKeyId(charge.pixKeyId());
            entity.setDueDate(charge.dueDate());
            entity.setValidityAfterDueDays(charge.validityAfterDueDays());
        }
        entity.setStatus(charge.status().name());
        entity.setOriginalAmount(charge.originalAmount().value());
        entity.setExpirationSeconds(charge.expirationSeconds());
        entity.setExpiresAt(charge.expiresAt());
        if (charge.debtor() == null) {
            entity.setDebtorType(null);
            entity.setDebtorDocumentEnc(null);
            entity.setDebtorDocumentHash(null);
            entity.setDebtorName(null);
        } else {
            entity.setDebtorType(charge.debtor().document().type());
            entity.setDebtorDocumentEnc(crypto.encrypt(charge.debtor().document().value()));
            entity.setDebtorDocumentHash(hashOf(charge.debtor().document()));
            entity.setDebtorName(charge.debtor().name());
        }
        entity.setPayerRequest(charge.payerRequest());
        entity.setLocationId(charge.location() == null ? null : charge.location().id());
        entity.setRevision(charge.revision());
        entity.setPaidReceivedId(charge.paidReceivedId());
        entity.setRemovedAt(charge.removedAt());
    }
}
