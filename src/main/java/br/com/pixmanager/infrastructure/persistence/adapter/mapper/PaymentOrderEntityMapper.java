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

    public PaymentOrder toDomain(PaymentOrderEntity entity) {
        Document document = entity.getDestDocumentEnc() == null ? null : Document.of(crypto.decrypt(entity.getDestDocumentEnc()));
        PaymentDestination destination = new PaymentDestination(entity.getDestinationKeyType() == null ? null : PixKeyType.valueOf(entity.getDestinationKeyType()),
                null, document, entity.getDestName(), entity.getDestIspb(), entity.getDestBranch(), entity.getDestAccount(), entity.getDestAccountType());
        return new PaymentOrder(entity.getId(), entity.getPayerAccountId(), PaymentMethod.valueOf(entity.getPaymentMethod()), PaymentOrigin.valueOf(entity.getOrigin()),
                destination, Money.of(entity.getAmount()), entity.getQrTxid(), entity.getPayerMessage(), new EndToEndId(entity.getEndToEndId()),
                PaymentStatus.valueOf(entity.getStatus()), entity.getRejectReasonCode(), entity.getRejectReasonDetail(), entity.getLedgerReservationId(),
                entity.isDeviceRegistered(), entity.getRequestedAt(), entity.getSentAt(), entity.getSettledAt(), entity.getVersion());
    }

    public void apply(PaymentOrder paymentOrder, PaymentOrderEntity entity, boolean isNew) {
        if (isNew) {
            entity.setPayerAccountId(paymentOrder.payerAccountId());
            entity.setPaymentMethod(paymentOrder.method().name());
            entity.setOrigin(paymentOrder.origin().name());
            entity.setDeviceRegistered(paymentOrder.deviceRegistered());
            PaymentDestination destination = paymentOrder.destination();
            entity.setDestinationKeyType(destination.keyType() == null ? null : destination.keyType().name());
            entity.setDestinationKeyHash(destination.pixKey() == null ? null : crypto.blindIndex(destination.pixKey()));
            if (destination.document() != null) {
                entity.setDestDocumentType(destination.document().type());
                entity.setDestDocumentEnc(crypto.encrypt(destination.document().value()));
                entity.setDestDocumentHash(crypto.blindIndex(destination.document().value()));
                entity.setDestDocumentMasked(destination.document().masked());
            }
            entity.setDestName(destination.name());
            entity.setDestIspb(destination.ispb());
            entity.setDestBranch(destination.branch());
            entity.setDestAccount(destination.account());
            entity.setDestAccountType(destination.accountType());
            entity.setAmount(paymentOrder.amount().value());
            entity.setQrTxid(paymentOrder.qrTxid());
            entity.setPayerMessage(paymentOrder.payerMessage());
            entity.setEndToEndId(paymentOrder.endToEndId().value());
            entity.setRequestedAt(paymentOrder.requestedAt());
        }
        entity.setStatus(paymentOrder.status().name());
        entity.setRejectReasonCode(paymentOrder.rejectReasonCode());
        entity.setRejectReasonDetail(paymentOrder.rejectReasonDetail() == null ? null : paymentOrder.rejectReasonDetail().substring(0, Math.min(255, paymentOrder.rejectReasonDetail().length())));
        entity.setLedgerReservationId(paymentOrder.ledgerReservationId());
        entity.setSentAt(paymentOrder.sentAt());
        entity.setSettledAt(paymentOrder.settledAt());
    }
}
