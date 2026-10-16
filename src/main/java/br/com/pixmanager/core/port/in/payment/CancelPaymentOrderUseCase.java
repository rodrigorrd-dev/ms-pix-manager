package br.com.pixmanager.core.port.in.payment;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.shared.model.ExpectedVersion;

import java.util.UUID;

public interface CancelPaymentOrderUseCase {

    /** Só antes de ir ao SPI (CREATED/VALIDATED/RESERVED). Cancela a reserva no core, devolve o limite e publica o evento. */
    PaymentOrder cancel(Caller caller, UUID id, ExpectedVersion expected);
}
