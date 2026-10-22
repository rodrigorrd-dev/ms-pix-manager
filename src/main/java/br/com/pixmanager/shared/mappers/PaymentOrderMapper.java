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

    public CreatePaymentOrderCommand toCommand(CreatePaymentOrderRequest request) {
        var d = request.destination();
        return new CreatePaymentOrderCommand(request.payerAccountId(), request.method(), null, request.amount() == null ? null : Money.parseBcb(request.amount()),
                d == null ? null : d.pixKeyType(), d == null ? null : d.pixKey(), d == null ? null : d.documentHint(),
                d == null ? null : d.ispb(), d == null ? null : d.branch(), d == null ? null : d.account(),
                d == null ? null : d.accountType(), d == null ? null : d.name(), d == null ? null : d.document(), request.qrPayload(),
                request.payerMessage(), request.deviceId(), false);
    }

    public PaymentOrderResponse toResponse(PaymentOrder paymentOrder) {
        var dest = paymentOrder.destination();
        return new PaymentOrderResponse(paymentOrder.id(), paymentOrder.payerAccountId(), paymentOrder.method(), paymentOrder.origin(), paymentOrder.status(), paymentOrder.amount().toBcb(),
                paymentOrder.endToEndId().value(), paymentOrder.rejectReasonCode(), dest.name(), dest.document() == null ? null : dest.document().masked(),
                paymentOrder.requestedAt(), paymentOrder.settledAt());
    }

    public BrCodeResponse toResponse(BrCode brCode) {
        return new BrCodeResponse(brCode.pixKey().flatMap(PixKeyValue::infer).map(PixKeyValue::masked).orElse(null), brCode.paymentUrl().orElse(null),
                brCode.amount().map(Money::toBcb).orElse(null), brCode.txid().orElse(null), brCode.merchantName().orElse(null),
                brCode.merchantCity().orElse(null), brCode.isDynamic());
    }
}
