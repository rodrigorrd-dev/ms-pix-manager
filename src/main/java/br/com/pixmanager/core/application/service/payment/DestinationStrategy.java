package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;

/** Como descobrir o favorecido para um {@link PaymentMethod}; um método novo é uma classe nova, sem mexer no resolvedor. */
interface DestinationStrategy {

    PaymentMethod method();

    PaymentDestinationResolver.Resolved resolve(Caller caller, CreatePaymentOrderCommand command);
}
