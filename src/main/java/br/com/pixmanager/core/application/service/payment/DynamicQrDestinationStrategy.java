package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.BrCode;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;
import br.com.pixmanager.core.port.out.DynamicQrPayloadPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;

import java.time.Clock;

/** QR dinâmico: o valor e a chave vêm do payload ASSINADO do recebedor, não do que o cliente digitou. */
class DynamicQrDestinationStrategy implements DestinationStrategy {

    private final KeyDestinationStrategy keyStrategy;
    private final DynamicQrPayloadPortOut dynamicQr;
    private final Clock clock;

    DynamicQrDestinationStrategy(KeyDestinationStrategy keyStrategy, DynamicQrPayloadPortOut dynamicQr, Clock clock) {
        this.keyStrategy = keyStrategy;
        this.dynamicQr = dynamicQr;
        this.clock = clock;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.QR_DYNAMIC;
    }

    @Override
    public PaymentDestinationResolver.Resolved resolve(Caller caller, CreatePaymentOrderCommand command) {
        BrCode brCode = BrCode.parse(command.qrPayload());
        if (!brCode.isDynamic()) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "O BR Code informado não é um QR dinâmico.");
        }
        var payload = dynamicQr.fetch(brCode.paymentUrl().get());
        if (!"ATIVA".equals(payload.status())) {
            throw new BusinessException(ErrorCodes.QR_DYNAMIC_INVALID, "A cobrança do QR não está ativa (status " + payload.status() + ").");
        }
        if (payload.expiresAt() != null && !clock.instant().isBefore(payload.expiresAt())) {
            throw new BusinessException(ErrorCodes.QR_DYNAMIC_INVALID, "A cobrança do QR está vencida.");
        }
        if (command.amount() != null && !command.amount().equals(payload.amount())) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "O valor informado difere do valor da cobrança.");
        }
        PixKeyValue key = PixKeyValue.infer(payload.pixKey())
                .orElseThrow(() -> new BusinessException(ErrorCodes.QR_DYNAMIC_INVALID, "A chave da cobrança é inválida."));
        return keyStrategy.resolveKey(caller, command, key.type(), key.value(), payload.amount(), payload.txid());
    }
}
