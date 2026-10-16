package br.com.pixmanager.shared.mappers;

import br.com.pixmanager.api.rest.payment.dto.request.CreatePaymentOrderRequest;
import br.com.pixmanager.api.rest.payment.dto.response.BrCodeResponse;
import br.com.pixmanager.api.rest.payment.dto.response.PaymentOrderResponse;
import br.com.pixmanager.core.domain.entity.BrCode;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;
import org.springframework.stereotype.Component;

@Component
public class PaymentOrderMapper {

    public CreatePaymentOrderCommand toCommand(CreatePaymentOrderRequest r) {
        var d = r.destination();
        return new CreatePaymentOrderCommand(r.payerAccountId(), r.method(), null, r.amount() == null ? null : Money.parseBcb(r.amount()),
                d == null ? null : d.pixKeyType(), d == null ? null : d.pixKey(), d == null ? null : d.documentHint(),
                d == null ? null : d.ispb(), d == null ? null : d.branch(), d == null ? null : d.account(),
                d == null ? null : d.accountType(), d == null ? null : d.name(), d == null ? null : d.document(), r.qrPayload(),
                r.payerMessage(), r.deviceId(), false);
    }

    public PaymentOrderResponse toResponse(PaymentOrder o) {
        var dest = o.destination();
        return new PaymentOrderResponse(o.id(), o.payerAccountId(), o.method(), o.origin(), o.status(), o.amount().toBcb(),
                o.endToEndId().value(), o.rejectReasonCode(), dest.name(), dest.document() == null ? null : dest.document().masked(),
                o.requestedAt(), o.settledAt());
    }

    public BrCodeResponse toResponse(BrCode qr) {
        return new BrCodeResponse(qr.pixKey().flatMap(PixKeyValue::infer).map(PixKeyValue::masked).orElse(null), qr.paymentUrl().orElse(null),
                qr.amount().map(Money::toBcb).orElse(null), qr.txid().orElse(null), qr.merchantName().orElse(null),
                qr.merchantCity().orElse(null), qr.isDynamic());
    }
}
