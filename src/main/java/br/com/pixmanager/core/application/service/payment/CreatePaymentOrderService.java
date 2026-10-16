package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.BrCode;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.LimitDecision;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentDestination;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.PixKeyValue;
import br.com.pixmanager.core.domain.entity.event.PaymentOrderEvent;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.ledger.RequestReservationUseCase;
import br.com.pixmanager.core.port.in.limit.ConsumeLimitUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase;
import br.com.pixmanager.core.port.in.pixkey.LookupExternalKeyUseCase;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.generator.EndToEndIdGeneratorPortOut;
import br.com.pixmanager.core.port.out.generator.IdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.core.port.out.persistence.TrustedDevicePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.document.Document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.pixmanager.core.port.out.DynamicQrPayloadPortOut;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * BR-PAY-01..07. ORDEM deliberada: (1) resolver o favorecido no DICT (HTTP via dict-gateway, FORA de transação); (2) UMA transação
 * com limite (UPDATE atômico) + reserva PENDING no core + ordem + evento. A chamada ao core acontece depois (sweeper) — nunca
 * dentro desta transação. Estouro de limite é VALOR ({@code Rejected}), e a ordem fica REJECTED para auditoria.
 */
public class CreatePaymentOrderService implements CreatePaymentOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentOrderService.class);

    private static final DynamicQrPayloadPortOut UNSUPPORTED = url -> {
        throw new BusinessException(ErrorCodes.BUSINESS_RULE, "QR dinâmico não está habilitado nesta instalação.");
    };

    private final FindAccountUseCase accounts;
    private final PaymentDestinationResolver destinations;
    private final ConsumeLimitUseCase limits;
    private final RequestReservationUseCase reservations;
    private final PaymentOrderPersistencePortOut persistence;
    private final TrustedDevicePortOut devices;
    private final DomainEventPublisherPortOut events;
    private final EndToEndIdGeneratorPortOut e2eIds;
    private final IdGeneratorPortOut ids;
    private final TransactionRunnerPortOut tx;
    private final Clock clock;

    public CreatePaymentOrderService(FindAccountUseCase accounts, LookupExternalKeyUseCase lookup, ConsumeLimitUseCase limits,
                                     RequestReservationUseCase reservations, PaymentOrderPersistencePortOut persistence,
                                     TrustedDevicePortOut devices, DomainEventPublisherPortOut events,
                                     EndToEndIdGeneratorPortOut e2eIds, IdGeneratorPortOut ids, TransactionRunnerPortOut tx, Clock clock) {
        this(accounts, lookup, limits, reservations, persistence, devices, events, e2eIds, ids, tx, clock, null);
    }

    /** {@code dynamicQr} nulo ⇒ QR dinâmico indisponível (422). */
    public CreatePaymentOrderService(FindAccountUseCase accounts, LookupExternalKeyUseCase lookup, ConsumeLimitUseCase limits,
                                     RequestReservationUseCase reservations, PaymentOrderPersistencePortOut persistence,
                                     TrustedDevicePortOut devices, DomainEventPublisherPortOut events,
                                     EndToEndIdGeneratorPortOut e2eIds, IdGeneratorPortOut ids, TransactionRunnerPortOut tx, Clock clock,
                                     DynamicQrPayloadPortOut dynamicQr) {
        this.accounts = accounts;
        this.destinations = new PaymentDestinationResolver(lookup, dynamicQr == null ? UNSUPPORTED : dynamicQr, clock);
        this.limits = limits;
        this.reservations = reservations;
        this.persistence = persistence;
        this.devices = devices;
        this.events = events;
        this.e2eIds = e2eIds;
        this.ids = ids;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public PreparedPaymentOrder prepare(Caller caller, CreatePaymentOrderCommand cmd) {
        if (!caller.canAccess(cmd.payerAccountId())) {
            throw new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada.");
        }
        AccountInfo payer = accounts.findById(cmd.payerAccountId())
                .orElseThrow(() -> new NotFoundException(ErrorCodes.NOT_FOUND, "Conta não encontrada."));
        if (!payer.isActive()) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE, "A conta do pagador não está ativa.");
        }
        PaymentDestinationResolver.Resolved resolved = destinations.resolve(caller, cmd);                                        // (1) HTTP fora de transação
        boolean deviceRegistered = cmd.systemInitiated()
                || (cmd.deviceId() != null && devices.isRegistered(payer.id(), cmd.deviceId()));

        PaymentOrder order = PaymentOrder.create(ids.newId(), payer.id(), cmd.method(),
                cmd.origin() == null ? PaymentOrigin.IMMEDIATE : cmd.origin(), resolved.destination(), resolved.amount(),
                resolved.qrTxid(), cmd.payerMessage(), new EndToEndId(e2eIds.nextEndToEndId()), deviceRegistered, clock.instant());

        return new PreparedPaymentOrder(order);
    }

    @Override
    public PaymentOrderResult submit(PreparedPaymentOrder prepared) {
        PaymentOrder order = prepared.order();
        UUID payerId = order.payerAccountId();
        boolean deviceRegistered = order.deviceRegistered();
        PaymentOrderResult result = tx.inTransaction(() -> {                             // (2) atômico: limite + reserva + ordem + evento
            LimitDecision decision = limits.consume(payerId, order.amount(), order.requestedAt(), deviceRegistered);
            if (decision instanceof LimitDecision.Rejected rejected) {
                PaymentOrder refused = persistence.save(order.rejected("AM02", "Limite excedido: " + rejected.scope()));
                events.publish(new PaymentOrderEvent("PaymentRejected", refused));
                return new PaymentOrderResult.Rejected(refused, ErrorCodes.LIMIT_EXCEEDED,
                        "O pagamento excede o limite %s (%s).".formatted(rejected.scope(), rejected.limit().toBcb()));
            }
            var reservation = reservations.request(order.endToEndId().value(), payerId, LedgerPurpose.PAYMENT, "PAYMENT_ORDER",
                    order.id(), order.amount());
            PaymentOrder saved = persistence.save(order.validated(reservation.id()));
            events.publish(new PaymentOrderEvent("PaymentOrderCreated", saved));
            return new PaymentOrderResult.Created(saved);
        });
        logOutcome(result);
        return result;
    }

    private static void logOutcome(PaymentOrderResult result) {
        switch (result) {
            case PaymentOrderResult.Created created -> log.info("Ordem de pagamento criada: id={} e2eId={} método={}", created.order().id(),
                    created.order().endToEndId().value(), created.order().method());
            case PaymentOrderResult.Rejected rejected -> log.info("Ordem de pagamento rejeitada por limite: id={} e2eId={} código={}",
                    rejected.order().id(), rejected.order().endToEndId().value(), rejected.errorCode());
        }
    }
}
