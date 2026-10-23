package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentDestination;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;
import br.com.pixmanager.core.port.in.pixkey.LookupExternalKeyUseCase;
import br.com.pixmanager.core.port.out.DynamicQrPayloadPortOut;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolve o favorecido do pagamento escolhendo a {@link DestinationStrategy} do método (chave, QR estático, QR dinâmico, manual);
 * a consulta ao DICT é HTTP e roda FORA de transação.
 */
class PaymentDestinationResolver {

    private final Map<PaymentMethod, DestinationStrategy> strategies = new EnumMap<>(PaymentMethod.class);

    PaymentDestinationResolver(LookupExternalKeyUseCase lookup, DynamicQrPayloadPortOut dynamicQr, Clock clock) {
        KeyDestinationStrategy key = new KeyDestinationStrategy(lookup);
        List.of(key,
                new StaticQrDestinationStrategy(key),
                new DynamicQrDestinationStrategy(key, dynamicQr, clock),
                new ManualDestinationStrategy())
            .forEach(strategy -> strategies.put(strategy.method(), strategy));
    }

    record Resolved(PaymentDestination destination, Money amount, String qrTxid) {}

    Resolved resolve(Caller caller, CreatePaymentOrderCommand command) {
        return strategies.get(command.method()).resolve(caller, command);
    }
}
