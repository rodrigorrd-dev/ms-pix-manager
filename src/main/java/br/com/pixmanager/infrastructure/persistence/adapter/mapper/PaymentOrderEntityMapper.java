package br.com.pixmanager.infrastructure.persistence.adapter.mapper;

import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentDestination;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.domain.enums.PaymentStatus;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.infrastructure.persistence.entity.PaymentOrderEntity;
import br.com.pixmanager.infrastructure.security.crypto.CryptoService;
import br.com.pixmanager.shared.model.document.Document;
import org.springframework.stereotype.Component;

/** O documento do favorecido é cifrado (AES-GCM) + índice cego (HMAC) só aqui; a coluna {@code *_masked} serve a telas/relatórios. */
@Component
public class PaymentOrderEntityMapper {

    private final CryptoService crypto;

    public PaymentOrderEntityMapper(CryptoService crypto) {
        this.crypto = crypto;
    }

    public PaymentOrder toDomain(PaymentOrderEntity e) {
        Document doc = e.getDestDocumentEnc() == null ? null : Document.of(crypto.decrypt(e.getDestDocumentEnc()));
        PaymentDestination destination = new PaymentDestination(e.getDestinationKeyType() == null ? null : PixKeyType.valueOf(e.getDestinationKeyType()),
                null, doc, e.getDestName(), e.getDestIspb(), e.getDestBranch(), e.getDestAccount(), e.getDestAccountType());
        return new PaymentOrder(e.getId(), e.getPayerAccountId(), PaymentMethod.valueOf(e.getPaymentMethod()), PaymentOrigin.valueOf(e.getOrigin()),
                destination, Money.of(e.getAmount()), e.getQrTxid(), e.getPayerMessage(), new EndToEndId(e.getEndToEndId()),
                PaymentStatus.valueOf(e.getStatus()), e.getRejectReasonCode(), e.getRejectReasonDetail(), e.getLedgerReservationId(),
                e.isDeviceRegistered(), e.getRequestedAt(), e.getSentAt(), e.getSettledAt(), e.getVersion());
    }

    public void apply(PaymentOrder d, PaymentOrderEntity e, boolean isNew) {
        if (isNew) {
            e.setPayerAccountId(d.payerAccountId());
            e.setPaymentMethod(d.method().name());
            e.setOrigin(d.origin().name());
            e.setDeviceRegistered(d.deviceRegistered());
            PaymentDestination dest = d.destination();
            e.setDestinationKeyType(dest.keyType() == null ? null : dest.keyType().name());
            e.setDestinationKeyHash(dest.pixKey() == null ? null : crypto.blindIndex(dest.pixKey()));
            if (dest.document() != null) {
                e.setDestDocumentType(dest.document().type());
                e.setDestDocumentEnc(crypto.encrypt(dest.document().value()));
                e.setDestDocumentHash(crypto.blindIndex(dest.document().value()));
                e.setDestDocumentMasked(dest.document().masked());
            }
            e.setDestName(dest.name());
            e.setDestIspb(dest.ispb());
            e.setDestBranch(dest.branch());
            e.setDestAccount(dest.account());
            e.setDestAccountType(dest.accountType());
            e.setAmount(d.amount().value());
            e.setQrTxid(d.qrTxid());
            e.setPayerMessage(d.payerMessage());
            e.setEndToEndId(d.endToEndId().value());
            e.setRequestedAt(d.requestedAt());
        }
        e.setStatus(d.status().name());
        e.setRejectReasonCode(d.rejectReasonCode());
        e.setRejectReasonDetail(d.rejectReasonDetail() == null ? null : d.rejectReasonDetail().substring(0, Math.min(255, d.rejectReasonDetail().length())));
        e.setLedgerReservationId(d.ledgerReservationId());
        e.setSentAt(d.sentAt());
        e.setSettledAt(d.settledAt());
    }
}
