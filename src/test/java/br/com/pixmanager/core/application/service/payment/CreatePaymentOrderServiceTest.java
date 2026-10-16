package br.com.pixmanager.core.application.service.payment;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.entity.LimitDecision;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PaymentOrder;
import br.com.pixmanager.core.domain.entity.event.PaymentOrderEvent;
import br.com.pixmanager.core.domain.enums.AccountStatus;
import br.com.pixmanager.core.domain.enums.HolderType;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.enums.LimitScope;
import br.com.pixmanager.core.domain.enums.PaymentMethod;
import br.com.pixmanager.core.domain.enums.PaymentOrigin;
import br.com.pixmanager.core.domain.enums.PaymentStatus;
import br.com.pixmanager.core.domain.enums.PixKeyType;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.ledger.RequestReservationUseCase;
import br.com.pixmanager.core.port.in.limit.ConsumeLimitUseCase;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.CreatePaymentOrderCommand;
import br.com.pixmanager.core.port.in.payment.CreatePaymentOrderUseCase.PaymentOrderResult;
import br.com.pixmanager.core.port.in.pixkey.LookupExternalKeyUseCase;
import br.com.pixmanager.core.port.out.dict.DictGatewayPortOut.DictEntry;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.generator.EndToEndIdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.PaymentOrderPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.TransactionRunnerPortOut;
import br.com.pixmanager.core.port.out.persistence.TrustedDevicePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreatePaymentOrderService — resolve favorecido fora da TX; limite + reserva + ordem + evento na mesma TX")
class CreatePaymentOrderServiceTest {

    static final String E2E = "E12345678202610151403abcdefghij0";
    static final String OWNER_CPF = "52998224725";
    static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");

    @Mock FindAccountUseCase accounts;
    @Mock LookupExternalKeyUseCase lookup;
    @Mock ConsumeLimitUseCase limits;
    @Mock RequestReservationUseCase reservations;
    @Mock PaymentOrderPersistencePortOut persistence;
    @Mock TrustedDevicePortOut devices;
    @Mock DomainEventPublisherPortOut events;
    @Mock EndToEndIdGeneratorPortOut e2eIds;

    final UUID payerId = SequentialIds.id(100);
    final Caller caller = Caller.forAccounts("client", payerId);
    CreatePaymentOrderService service;

    @BeforeEach
    void setUp() {
        TransactionRunnerPortOut tx = new TransactionRunnerPortOut() {
            @Override
            public <T> T inTransaction(Supplier<T> action) {
                return action.get();
            }
        };
        service = new CreatePaymentOrderService(accounts, lookup, limits, reservations, persistence, devices, events, e2eIds,
                new SequentialIds(), tx, FixedClock.at("2026-10-15T15:00:00Z"));
    }

    @Test
    void shouldCreateValidatedOrderWithReservationAndEventWhenLimitAllows() {
        activePayer();
        stubKeyLookup();
        when(e2eIds.nextEndToEndId()).thenReturn(E2E);
        when(devices.isRegistered(payerId, "dev-1")).thenReturn(true);
        when(limits.consume(eq(payerId), eq(Money.of("150.00")), eq(NOW), eq(true)))
                .thenReturn(new LimitDecision.Allowed(null, true, Money.of("150.00")));
        UUID reservationId = SequentialIds.id(900);
        when(reservations.request(eq(E2E), eq(payerId), eq(LedgerPurpose.PAYMENT), eq("PAYMENT_ORDER"), any(UUID.class), eq(Money.of("150.00"))))
                .thenReturn(reservation(reservationId));
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentOrderResult result = service.create(caller, byKey("150.00", "dev-1"));

        assertThat(result).isInstanceOf(PaymentOrderResult.Created.class);
        PaymentOrder order = ((PaymentOrderResult.Created) result).order();
        assertThat(order.status()).isEqualTo(PaymentStatus.VALIDATED);
        assertThat(order.ledgerReservationId()).isEqualTo(reservationId);
        assertThat(order.endToEndId().value()).isEqualTo(E2E);
        assertThat(order.deviceRegistered()).isTrue();
        assertThat(order.origin()).as("origem padrão").isEqualTo(PaymentOrigin.IMMEDIATE);
        assertThat(publishedEvent().eventType()).isEqualTo("PaymentOrderCreated");
    }

    @Test
    @DisplayName("BR-LIM-06: limite estourado vira ordem REJECTED (auditoria) e resultado Rejected, sem reserva")
    void shouldPersistRejectedOrderAndSkipReservationWhenLimitIsExceeded() {
        activePayer();
        stubKeyLookup();
        when(e2eIds.nextEndToEndId()).thenReturn(E2E);
        when(limits.consume(any(), any(), any(), anyBoolean()))
                .thenReturn(new LimitDecision.Rejected(LimitScope.DAILY, Money.of("5000.00")));
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentOrderResult result = service.create(caller, byKey("6000.00", null));

        assertThat(result).isInstanceOf(PaymentOrderResult.Rejected.class);
        var rejected = (PaymentOrderResult.Rejected) result;
        assertThat(rejected.errorCode()).isEqualTo(ErrorCodes.LIMIT_EXCEEDED);
        assertThat(rejected.order().status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(rejected.order().rejectReasonCode()).isEqualTo("AM02");
        assertThat(publishedEvent().eventType()).isEqualTo("PaymentRejected");
        verifyNoInteractions(reservations);
    }

    @Test
    @DisplayName("Sem deviceId, o dispositivo é tratado como NÃO cadastrado (padrão seguro)")
    void shouldTreatMissingDeviceAsUnregistered() {
        activePayer();
        stubKeyLookup();
        when(e2eIds.nextEndToEndId()).thenReturn(E2E);
        when(limits.consume(any(), any(), any(), eq(false))).thenReturn(new LimitDecision.Rejected(LimitScope.DAILY, Money.of("1.00")));
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(caller, byKey("10.00", null));

        verify(limits).consume(payerId, Money.of("10.00"), NOW, false);
        verifyNoInteractions(devices);
    }

    @Test
    void shouldSkipDeviceLookupWhenSystemInitiated() {
        activePayer();
        stubKeyLookup();
        when(e2eIds.nextEndToEndId()).thenReturn(E2E);
        when(limits.consume(any(), any(), any(), eq(true))).thenReturn(new LimitDecision.Rejected(LimitScope.DAILY, Money.of("1.00")));
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        var cmd = new CreatePaymentOrderCommand(payerId, PaymentMethod.KEY, PaymentOrigin.SCHEDULED, Money.of("10.00"), PixKeyType.CPF,
                OWNER_CPF, null, null, null, null, null, null, null, null, null, null, true);

        service.create(Caller.system("scheduler"), cmd);

        verifyNoInteractions(devices);
    }

    @Test
    void shouldHideAccountOutsideTheCallerScopeAsNotFound() {
        var cmd = byKey("10.00", null);
        Caller other = Caller.forAccounts("other", SequentialIds.id(7));

        assertThatThrownBy(() -> service.create(other, cmd)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(accounts, lookup, limits, persistence);
    }

    @Test
    void shouldRejectInactivePayerAccountBeforeAnyExternalCall() {
        when(accounts.findById(payerId)).thenReturn(Optional.of(payer(AccountStatus.BLOCKED)));

        assertThatThrownBy(() -> service.create(caller, byKey("10.00", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCodes.BUSINESS_RULE));

        verifyNoInteractions(lookup, limits, persistence);
    }

    @Test
    void shouldFailWhenPixKeyIsNotFoundInTheDict() {
        activePayer();
        when(lookup.lookup(any(), eq(payerId), eq(PixKeyType.CPF), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(caller, byKey("10.00", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCodes.INVALID_PIX_KEY));

        verifyNoInteractions(limits, persistence);
    }

    @Test
    @DisplayName("BR-PAY-04: documento esperado diferente do dono da chave ⇒ DESTINATION_MISMATCH")
    void shouldRejectWhenDocumentHintDoesNotMatchTheKeyOwner() {
        activePayer();
        stubKeyLookup();
        var cmd = new CreatePaymentOrderCommand(payerId, PaymentMethod.KEY, null, Money.of("10.00"), PixKeyType.CPF, OWNER_CPF,
                "11144477735", null, null, null, null, null, null, null, null, null, false);

        assertThatThrownBy(() -> service.create(caller, cmd))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ErrorCodes.DESTINATION_MISMATCH));

        verify(limits, never()).consume(any(), any(), any(), anyBoolean());
    }

    @Test
    void shouldRequireAmountWhenPayingByKey() {
        activePayer();
        var cmd = new CreatePaymentOrderCommand(payerId, PaymentMethod.KEY, null, null, PixKeyType.CPF, OWNER_CPF,
                null, null, null, null, null, null, null, null, null, null, false);

        assertThatThrownBy(() -> service.create(caller, cmd)).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(lookup, limits);
    }

    @Test
    void shouldRequireKeyTypeAndValueWhenPayingByKey() {
        activePayer();
        var cmd = new CreatePaymentOrderCommand(payerId, PaymentMethod.KEY, null, Money.of("10.00"), null, null,
                null, null, null, null, null, null, null, null, null, null, false);

        assertThatThrownBy(() -> service.create(caller, cmd)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void shouldRejectManualPaymentWithMalformedIspb() {
        activePayer();
        var cmd = new CreatePaymentOrderCommand(payerId, PaymentMethod.MANUAL, null, Money.of("10.00"), null, null, null,
                "123", "0001", "12345", "CACC", "Fulano", OWNER_CPF, null, null, null, false);

        assertThatThrownBy(() -> service.create(caller, cmd)).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(lookup, limits);
    }

    @Test
    void shouldNotResolveDestinationThroughTheDictForManualPayment() {
        activePayer();
        when(e2eIds.nextEndToEndId()).thenReturn(E2E);
        when(limits.consume(any(), any(), any(), anyBoolean())).thenReturn(new LimitDecision.Rejected(LimitScope.DAILY, Money.of("1.00")));
        when(persistence.save(any(PaymentOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        var cmd = new CreatePaymentOrderCommand(payerId, PaymentMethod.MANUAL, null, Money.of("10.00"), null, null, null,
                "12345678", "0001", "12345", "CACC", "Fulano", OWNER_CPF, null, null, null, false);

        service.create(caller, cmd);

        verifyNoInteractions(lookup);
        var saved = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(persistence).save(saved.capture());
        assertThat(saved.getValue().destination().ispb()).isEqualTo("12345678");
        assertThat(saved.getValue().destination().pixKey()).isNull();
    }

    @Test
    void shouldRefuseDynamicQrCodeAsNotYetSupported() {
        activePayer();
        var cmd = new CreatePaymentOrderCommand(payerId, PaymentMethod.QR_DYNAMIC, null, Money.of("10.00"), null, null, null,
                null, null, null, null, null, null, "payload", null, null, false);

        assertThatThrownBy(() -> service.create(caller, cmd)).isInstanceOf(BusinessException.class);

        verifyNoInteractions(lookup, limits);
    }

    // ------------------------------------------------------------------ helpers

    private void activePayer() {
        when(accounts.findById(payerId)).thenReturn(Optional.of(payer(AccountStatus.ACTIVE)));
    }

    private AccountInfo payer(AccountStatus status) {
        return new AccountInfo(payerId, SequentialIds.id(200), HolderType.INDIVIDUAL, Cpf.of("11144477735"), "hash", "Pagador",
                "12345678", "0001", "1", "CACC", status);
    }

    private void stubKeyLookup() {
        when(lookup.lookup(any(), eq(payerId), eq(PixKeyType.CPF), eq(OWNER_CPF))).thenReturn(Optional.of(
                new DictEntry("Favorecido", Cpf.of(OWNER_CPF), "87654321", "0002", "98765", "CACC", NOW)));
    }

    private CreatePaymentOrderCommand byKey(String amount, String deviceId) {
        return new CreatePaymentOrderCommand(payerId, PaymentMethod.KEY, null, Money.of(amount), PixKeyType.CPF, OWNER_CPF,
                null, null, null, null, null, null, null, null, null, deviceId, false);
    }

    private LedgerReservation reservation(UUID id) {
        return LedgerReservation.pending(id, E2E, payerId, LedgerPurpose.PAYMENT, "PAYMENT_ORDER", UUID.randomUUID(), Money.of("150.00"), NOW);
    }

    private PaymentOrderEvent publishedEvent() {
        var captor = ArgumentCaptor.forClass(PaymentOrderEvent.class);
        verify(events).publish(captor.capture());
        return captor.getValue();
    }
}
