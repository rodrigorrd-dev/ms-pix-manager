package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PaymentDestination;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.model.document.Document;

/** Pagamento manual: o favorecido vem inteiro no comando (dados bancários), sem consulta externa. */
class ManualDestinationStrategy implements DestinationStrategy {

    @Override
    public PaymentMethod method() {
        return PaymentMethod.MANUAL;
    }

    @Override
    public PaymentDestinationResolver.Resolved resolve(Caller caller, CreatePaymentOrderCommand command) {
        if (command.ispb() == null || !command.ispb().matches("\\d{8}") || command.branch() == null || command.account() == null || command.accountType() == null
                || command.name() == null || command.amount() == null) {
            throw new BadRequestException(ErrorCodes.INVALID_PAYLOAD, "Pagamento manual exige ispb, agência, conta, tipo, nome e valor.");
        }
        Document document = command.document() == null ? null : Document.of(command.document());   // CPF/CNPJ com dígito verificador
        return new PaymentDestinationResolver.Resolved(new PaymentDestination(null, null, document, command.name(), command.ispb(), command.branch(), command.account(), command.accountType()),
                command.amount(), null);
    }
}
