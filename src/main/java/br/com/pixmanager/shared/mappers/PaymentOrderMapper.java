package br.com.pixmanager.shared.mappers;

import br.com.pixmanager.shared.model.document.Document;
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
        var destination = CreatePaymentOrderRequest.Destination.orEmpty(request.destination());
        return new CreatePaymentOrderCommand(request.payerAccountId(), request.method(), null, Money.parseBcbOrNull(request.amount()),
                destination.pixKeyType(), destination.pixKey(), destination.documentHint(),
                destination.ispb(), destination.branch(), destination.account(),
                destination.accountType(), destination.name(), destination.document(), request.qrPayload(),
                request.payerMessage(), request.deviceId(), false);
    }

    public PaymentOrderResponse toResponse(PaymentOrder paymentOrder) {
        var destination = paymentOrder.destination();
        return new PaymentOrderResponse(paymentOrder.id(), paymentOrder.payerAccountId(), paymentOrder.method(), paymentOrder.origin(), paymentOrder.status(), paymentOrder.amount().toBcb(),
                paymentOrder.endToEndId().value(), paymentOrder.rejectReasonCode(), destination.name(), Document.maskedOrNull(destination.document()),
                paymentOrder.requestedAt(), paymentOrder.settledAt());
    }

    public BrCodeResponse toResponse(BrCode brCode) {
        return new BrCodeResponse(brCode.pixKey().flatMap(PixKeyValue::infer).map(PixKeyValue::masked).orElse(null), brCode.paymentUrl().orElse(null),
                brCode.amount().map(Money::toBcb).orElse(null), brCode.txid().orElse(null), brCode.merchantName().orElse(null),
                brCode.merchantCity().orElse(null), brCode.isDynamic());
    }
}
