package br.com.pixmanager.core.application.service.refund;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.LedgerReservation;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PixPayer;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.Refund;
import br.com.pixmanager.core.domain.entity.event.RefundEvent;
import br.com.pixmanager.core.domain.enums.LedgerPurpose;
import br.com.pixmanager.core.domain.enums.RefundNature;
import br.com.pixmanager.core.domain.enums.RefundStatus;
import br.com.pixmanager.core.port.in.ledger.RequestReservationUseCase;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase.RequestRefundCommand;
import br.com.pixmanager.core.port.in.refund.RequestRefundUseCase.RequestRefundResult;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.generator.EndToEndIdGeneratorPortOut;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.RefundPersistencePortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BusinessException;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RequestRefundService — idempotência por (e2e, id), prazo, teto devolvível atômico e reserva PENDING")
class RequestRefundServiceTest {

    static final String E2E = "E12345678202610151403abcdefghij0";
    static final String RTR = "D12345678202610151403abcdefghij0";
    static final String CLIENT_ID = "dev001";
    static final Duration WINDOW = Duration.ofDays(90);
    static final Instant SETTLED = Instant.parse("2026-10-01T12:00:00Z");
    static final Instant NOW = Instant.parse("2026-10-15T15:00:00Z");

    @Mock ReceivedPixPersistencePortOut receivedPix;
    @Mock RefundPersistencePortOut refunds;
    @Mock RequestReservationUseCase reservations;
    @Mock DomainEventPublisherPortOut events;
    @Mock EndToEndIdGeneratorPortOut endToEndIdGenerator;

    final UUID receiverAccount = SequentialIds.id(10);
    final Caller caller = Caller.forAccounts("client", receiverAccount);
    FixedClock clock = FixedClock.at("2026-10-15T15:00:00Z");
    RequestRefundService service;

    @BeforeEach
    void setUp() {
        service = new RequestRefundService(receivedPix, refunds, reservations, events, endToEndIdGenerator, new SequentialIds(), clock, WINDOW);
    }

    @Test
    void shouldCreateRefundReserveRefundableAmountAndRequestLedgerReservation() {
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(pix()));
        when(refunds.findByEndToEndIdAndClientId(E2E, CLIENT_ID)).thenReturn(Optional.empty());
        when(endToEndIdGenerator.nextReturnId()).thenReturn(RTR);
        when(receivedPix.reserveRefundable(any(UUID.class), eq(Money.of("40.00")))).thenReturn(true);
        when(refunds.save(any(Refund.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RequestRefundResult result = service.request(caller, command("40.00", RefundNature.ORIGINAL, "devolução"));

        assertThat(result).isInstanceOf(RequestRefundResult.Created.class);
        Refund refund = ((RequestRefundResult.Created) result).refund();
        assertThat(refund.status()).isEqualTo(RefundStatus.EM_PROCESSAMENTO);
        assertThat(refund.rtrId()).isEqualTo(RTR);
        assertThat(refund.amount()).isEqualByComparingTo(Money.of("40.00"));
        verify(reservations).request(RTR, receiverAccount, LedgerPurpose.REFUND, "REFUND", refund.id(), Money.of("40.00"));
        var event = ArgumentCaptor.forClass(RefundEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("RefundRequested");
    }

    @Test
    @DisplayName("BR-REF-01: repetição idêntica devolve o MESMO recurso, sem novo efeito")
    void shouldReplayIdenticalRequestWithoutSideEffects() {
        Refund existing = existingRefund("40.00", RefundNature.ORIGINAL, "devolução");
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(pix()));
        when(refunds.findByEndToEndIdAndClientId(E2E, CLIENT_ID)).thenReturn(Optional.of(existing));

        RequestRefundResult result = service.request(caller, command("40.00", RefundNature.ORIGINAL, "devolução"));

        assertThat(result).isEqualTo(new RequestRefundResult.Replayed(existing));
        verify(receivedPix, never()).reserveRefundable(any(), any());
        verify(refunds, never()).save(any());
        verifyNoInteractions(reservations, events);
    }

    @Test
    @DisplayName("BR-REF-01: mesmo id com valor diferente ⇒ IDEMPOTENCY_MISMATCH")
    void shouldRejectSameClientIdWithDifferentBody() {
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(pix()));
        when(refunds.findByEndToEndIdAndClientId(E2E, CLIENT_ID))
                .thenReturn(Optional.of(existingRefund("40.00", RefundNature.ORIGINAL, "devolução")));

        assertThatThrownBy(() -> service.request(caller, command("41.00", RefundNature.ORIGINAL, "devolução")))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(ErrorCodes.IDEMPOTENCY_MISMATCH));

        verify(refunds, never()).save(any());
        verifyNoInteractions(reservations, events);
    }

    @Test
    @DisplayName("BR-REF-03: valor acima do devolvível ⇒ nada é gravado, reservado nem publicado")
    void shouldRejectWhenAmountExceedsRefundableAndPersistNothing() {
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(pix()));
        when(refunds.findByEndToEndIdAndClientId(E2E, CLIENT_ID)).thenReturn(Optional.empty());
        when(endToEndIdGenerator.nextReturnId()).thenReturn(RTR);
        when(receivedPix.reserveRefundable(any(UUID.class), any(Money.class))).thenReturn(false);

        assertThatThrownBy(() -> service.request(caller, command("500.00", RefundNature.ORIGINAL, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(ErrorCodes.REFUND_EXCEEDS_AMOUNT));

        verify(refunds, never()).save(any());
        verifyNoInteractions(reservations, events);
    }

    @Test
    @DisplayName("BR-REF-05: dentro do prazo no último instante é aceito; um segundo depois, recusado")
    void shouldHonorTheRefundWindowBoundary() {
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(pix()));
        when(refunds.findByEndToEndIdAndClientId(anyString(), anyString())).thenReturn(Optional.empty());
        when(endToEndIdGenerator.nextReturnId()).thenReturn(RTR);
        when(receivedPix.reserveRefundable(any(UUID.class), any(Money.class))).thenReturn(true);
        when(refunds.save(any(Refund.class))).thenAnswer(invocation -> invocation.getArgument(0));

        clock.set(SETTLED.plus(WINDOW));
        assertThat(service.request(caller, command("10.00", RefundNature.ORIGINAL, null)))
                .isInstanceOf(RequestRefundResult.Created.class);

        clock.set(SETTLED.plus(WINDOW).plusSeconds(1));
        assertThatThrownBy(() -> service.request(caller, command("10.00", RefundNature.ORIGINAL, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(ErrorCodes.REFUND_OUT_OF_WINDOW));
    }

    @ParameterizedTest
    @EnumSource(value = RefundNature.class, names = {"MED_OPERACIONAL", "MED_FRAUDE", "MED_PIX_AUTOMATICO"})
    @DisplayName("BR-REF-06: natureza MED não é aceita por esta porta")
    void shouldRefuseMedNatures(RefundNature nature) {
        assertThatThrownBy(() -> service.request(caller, command("10.00", nature, null)))
                .as("natureza %s", nature)
                .isInstanceOf(BusinessException.class);

        verify(receivedPix, never()).reserveRefundable(any(), any());
        verifyNoInteractions(receivedPix, refunds, reservations, events);       // falha antes de qualquer consulta
    }

    @ParameterizedTest
    @EnumSource(value = RefundNature.class, names = {"ORIGINAL", "RETIRADA"})
    @DisplayName("O fluxo MED (requestMed) recusa natureza comum")
    void shouldRefuseNonMedNaturesOnTheMedEntry(RefundNature nature) {
        assertThatThrownBy(() -> service.requestMed(caller, command("10.00", nature, null))).isInstanceOf(BusinessException.class);

        verifyNoInteractions(receivedPix, refunds, reservations, events);
    }

    @Test
    void shouldHidePixOwnedByAnotherAccountAsNotFound() {
        Caller other = Caller.forAccounts("other", SequentialIds.id(99));
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.of(pix()));

        assertThatThrownBy(() -> service.request(other, command("10.00", RefundNature.ORIGINAL, null)))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(refunds, reservations, events);
    }

    @Test
    void shouldReturnNotFoundWhenThePixDoesNotExist() {
        when(receivedPix.findByEndToEndId(E2E)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(caller, command("10.00", RefundNature.ORIGINAL, null)))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ helpers

    private ReceivedPix pix() {
        return ReceivedPix.receive(SequentialIds.id(1), new EndToEndId(E2E), null, receiverAccount, Money.of("100.00"),
                new PixPayer(Cpf.of("52998224725"), "Pagador", "87654321", "0001", "123", "CACC"), null, SETTLED, "msg-1",
                List.of(), SETTLED);
    }

    private RequestRefundCommand command(String amount, RefundNature nature, String description) {
        return new RequestRefundCommand(E2E, CLIENT_ID, Money.of(amount), nature, description);
    }

    private Refund existingRefund(String amount, RefundNature nature, String description) {
        return Refund.request(SequentialIds.id(50), CLIENT_ID, SequentialIds.id(1), new EndToEndId(E2E), RTR, Money.of(amount), nature,
                description, NOW);
    }
}
