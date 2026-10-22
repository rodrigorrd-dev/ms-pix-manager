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
        var destination = request.destination();
        return new CreatePaymentOrderCommand(request.payerAccountId(), request.method(), null, request.amount() == null ? null : Money.parseBcb(request.amount()),
                destination == null ? null : destination.pixKeyType(), destination == null ? null : destination.pixKey(), destination == null ? null : destination.documentHint(),
                destination == null ? null : destination.ispb(), destination == null ? null : destination.branch(), destination == null ? null : destination.account(),
                destination == null ? null : destination.accountType(), destination == null ? null : destination.name(), destination == null ? null : destination.document(), request.qrPayload(),
                request.payerMessage(), request.deviceId(), false);
    }

    public PaymentOrderResponse toResponse(PaymentOrder paymentOrder) {
        var destination = paymentOrder.destination();
        return new PaymentOrderResponse(paymentOrder.id(), paymentOrder.payerAccountId(), paymentOrder.method(), paymentOrder.origin(), paymentOrder.status(), paymentOrder.amount().toBcb(),
                paymentOrder.endToEndId().value(), paymentOrder.rejectReasonCode(), destination.name(), destination.document() == null ? null : destination.document().masked(),
                paymentOrder.requestedAt(), paymentOrder.settledAt());
    }

    public BrCodeResponse toResponse(BrCode brCode) {
        return new BrCodeResponse(brCode.pixKey().flatMap(PixKeyValue::infer).map(PixKeyValue::masked).orElse(null), brCode.paymentUrl().orElse(null),
                brCode.amount().map(Money::toBcb).orElse(null), brCode.txid().orElse(null), brCode.merchantName().orElse(null),
                brCode.merchantCity().orElse(null), brCode.isDynamic());
    }
}
