package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentDestination;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;
import br.com.pixmanager.core.port.in.pixkey.LookupExternalKeyUseCase;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.model.document.Document;

/** Pagamento por chave: consulta o DICT. Também é a base dos QR (estático e dinâmico), que só descobrem a chave por outro caminho. */
class KeyDestinationStrategy implements DestinationStrategy {

    private final LookupExternalKeyUseCase lookup;

    KeyDestinationStrategy(LookupExternalKeyUseCase lookup) {
        this.lookup = lookup;
    }

    @Override
    public PaymentMethod method() {
        return PaymentMethod.KEY;
    }

    @Override
    public PaymentDestinationResolver.Resolved resolve(Caller caller, CreatePaymentOrderCommand command) {
        return resolveKey(caller, command, command.pixKeyType(), command.pixKey(), command.amount(), null);
    }

    PaymentDestinationResolver.Resolved resolveKey(Caller caller, CreatePaymentOrderCommand command, PixKeyType type, String rawKey, Money amount, String qrTxid) {
        if (type == null || rawKey == null || rawKey.isBlank()) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O pagamento por chave exige tipo e valor da chave.");
        }
        if (amount == null) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "O valor do pagamento é obrigatório.");
        }
        var entry = lookup.lookup(caller, command.payerAccountId(), type, rawKey)
                .orElseThrow(() -> new BusinessException(ErrorCodes.INVALID_PIX_KEY, "Chave Pix não encontrada."));
        if (command.documentHint() != null && !command.documentHint().isBlank()) {
            Document expected = Document.of(command.documentHint());                          // BR-DOC: dígito verificador
            if (!expected.value().equals(entry.ownerDocument().value())) {
                throw new BusinessException(ErrorCodes.DESTINATION_MISMATCH, "O documento do favorecido não confere com o da chave.");
            }
        }
        return new PaymentDestinationResolver.Resolved(new PaymentDestination(type, rawKey, entry.ownerDocument(), entry.ownerName(), entry.ispb(), entry.branch(),
                entry.accountNumber(), entry.accountType()), amount, qrTxid);
    }
}
