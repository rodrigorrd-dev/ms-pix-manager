package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.BrCode;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;

import java.util.Optional;

/** QR estático: a chave e (opcionalmente) o valor vêm do BR Code; o resto segue o caminho da chave. */
class StaticQrDestinationStrategy implements DestinationStrategy {

    private final KeyDestinationStrategy keyStrategy;

    StaticQrDestinationStrategy(KeyDestinationStrategy keyStrategy) {
        this.keyStrategy = keyStrategy;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.QR_STATIC;
    }

    @Override
    public PaymentDestinationResolver.Resolved resolve(Caller caller, CreatePaymentOrderCommand command) {
        BrCode brCode = BrCode.parse(command.qrPayload());
        if (brCode.isDynamic() || brCode.pixKey().isEmpty()) {
            throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "O BR Code informado não é um QR estático com chave.");
        }
        PixKeyValue key = PixKeyValue.infer(brCode.pixKey().get())
                .orElseThrow(() -> new BusinessException(ErrorCodes.INVALID_PIX_KEY, "A chave do BR Code é inválida."));
        Money amount = amountFor(command.amount(), brCode.amount());
        return keyStrategy.resolveKey(caller, command, key.type(), key.value(), amount, brCode.txid().orElse(null));
    }

    /** QR com valor fixo: o valor informado, se houver, precisa ser igual; sem valor no QR, o informado é obrigatório. */
    private static Money amountFor(Money informed, Optional<Money> inQr) {
        if (inQr.isPresent()) {
            if (informed != null && !informed.equals(inQr.get())) {
                throw new BusinessException(ErrorCodes.INVALID_PAYLOAD, "O valor informado difere do valor do BR Code.");
            }
            return inQr.get();
        }
        return informed;
    }
}
