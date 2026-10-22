package br.com.pixmanager.core.application.service.receivedpix;

import br.com.pixmanager.core.domain.entity.AccountInfo;
import br.com.pixmanager.core.domain.entity.ChargeSettlement;
import br.com.pixmanager.core.domain.entity.EndToEndId;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PixPayer;
import br.com.pixmanager.core.domain.entity.ReceivedPix;
import br.com.pixmanager.core.domain.entity.ValueComponent;
import br.com.pixmanager.core.domain.entity.event.PixReceivedEvent;
import br.com.pixmanager.core.domain.enums.AccountStatus;
import br.com.pixmanager.core.domain.enums.ComponentType;
import br.com.pixmanager.core.domain.enums.HolderType;
import br.com.pixmanager.core.domain.enums.ReconciliationStatus;
import br.com.pixmanager.core.port.in.account.FindAccountUseCase;
import br.com.pixmanager.core.port.in.charge.SettleChargeUseCase;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase.ReceivePixCommand;
import br.com.pixmanager.core.port.in.receivedpix.ReceivePixUseCase.ReceivePixResult;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.event.InboxPortOut;
import br.com.pixmanager.core.port.out.persistence.ReceivedPixPersistencePortOut;
import br.com.pixmanager.shared.exception.NotFoundException;
import br.com.pixmanager.shared.model.document.Cpf;
import br.com.pixmanager.support.FixedClock;
import br.com.pixmanager.support.SequentialIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

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
@DisplayName("ReceivePixService — inbox, duplicata por e2eId, conciliação com a cobrança; o SPI já liquidou, então nunca rejeita")
class ReceivePixServiceTest {

    static final String E2E = "E87654321202610151403abcdefghij0";
    static final String TXID = "cobranca0000000000000000000001";
    static final Instant SETTLED = Instant.parse("2026-10-15T14:03:00Z");

    @Mock InboxPortOut inbox;
    @Mock ReceivedPixPersistencePortOut persistence;
    @Mock FindAccountUseCase accounts;
    @Mock SettleChargeUseCase charges;
    @Mock DomainEventPublisherPortOut events;

    final UUID receiverId = SequentialIds.id(10);
    ReceivePixService service;

    @BeforeEach
    void setUp() {
        service = new ReceivePixService(inbox, persistence, accounts, charges, events, new SequentialIds(),
                FixedClock.at("2026-10-15T14:03:05Z"));
    }

    @Test
    void shouldSkipEverythingWhenTheMessageWasAlreadyProcessed() {
        when(inbox.firstTime("grp", "msg-1", "pix.spi.received.v1", 0, 7L)).thenReturn(false);

        assertThat(service.receive(command(TXID, "100.00", "52998224725", List.of()))).isInstanceOf(ReceivePixResult.Duplicate.class);

        verifyNoInteractions(persistence, accounts, charges, events);
    }

    @Test
    @DisplayName("BR-PIX-01: outra mensagem com o mesmo endToEndId também é duplicata")
    void shouldTreatSameEndToEndIdFromAnotherMessageAsDuplicate() {
        when(inbox.firstTime(anyString(), anyString(), anyString(), eq(0), eq(7L))).thenReturn(true);
        when(persistence.findByEndToEndId(E2E)).thenReturn(Optional.of(storedPix()));

        assertThat(service.receive(command(TXID, "100.00", "52998224725", List.of()))).isInstanceOf(ReceivePixResult.Duplicate.class);

        verify(persistence, never()).save(any());
        verifyNoInteractions(charges, events);
    }

    @Test
    void shouldFailWithNotFoundWhenTheReceiverAccountIsUnknown() {
        firstDelivery();
        when(persistence.findByEndToEndId(E2E)).thenReturn(Optional.empty());
        when(accounts.findByIdentity("12345678", "0001", "99", "CACC")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(command(TXID, "100.00", "52998224725", List.of())))
                .isInstanceOf(NotFoundException.class);

        verify(persistence, never()).save(any());
        verifyNoInteractions(charges, events);
    }

    @ParameterizedTest(name = "{0} ⇒ {1}")
    @MethodSource("settlements")
    @DisplayName("Resultado da conciliação da cobrança vira o ReconciliationStatus do Pix")
    void shouldMapChargeSettlementToReconciliationStatus(ChargeSettlement settlement, ReconciliationStatus expected, UUID expectedCharge) {
        firstDelivery();
        knownReceiver();
        when(charges.settle(eq(receiverId), eq(TXID), any(UUID.class), eq(Money.of("100.00")), eq(SETTLED))).thenReturn(settlement);
        when(persistence.save(any(ReceivedPix.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReceivedPix pix = ((ReceivePixResult.Received) service.receive(command(TXID, "100.00", "52998224725", List.of()))).pix();

        assertThat(pix.reconciliationStatus()).isEqualTo(expected);
        assertThat(pix.chargeId()).isEqualTo(expectedCharge);
        assertThat(publishedEvent()).isNotNull();
    }

    static Stream<Arguments> settlements() {
        UUID charge = SequentialIds.id(77);
        return Stream.of(
                Arguments.of(new ChargeSettlement.Settled(charge), ReconciliationStatus.MATCHED, charge),
                Arguments.of(new ChargeSettlement.PaidAfterExpiration(charge), ReconciliationStatus.MATCHED_AFTER_EXPIRATION, charge),
                Arguments.of(new ChargeSettlement.AmountMismatch(charge), ReconciliationStatus.MISMATCH, charge),
                Arguments.of(new ChargeSettlement.NotPayable(charge), ReconciliationStatus.NO_CHARGE, null),
                Arguments.of(new ChargeSettlement.NotFound(), ReconciliationStatus.NO_CHARGE, null));
    }

    @Test
    void shouldNotTryToSettleAChargeWhenThePixHasNoTxid() {
        firstDelivery();
        knownReceiver();
        when(persistence.save(any(ReceivedPix.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReceivedPix pix = ((ReceivePixResult.Received) service.receive(command(null, "100.00", "52998224725", List.of()))).pix();

        assertThat(pix.reconciliationStatus()).isEqualTo(ReconciliationStatus.NO_CHARGE);
        verifyNoInteractions(charges);
    }

    @Test
    @DisplayName("Documento do pagador mascarado/ inválido não derruba um Pix já liquidado")
    void shouldKeepThePixWithoutPayerDocumentWhenItIsInvalid() {
        firstDelivery();
        knownReceiver();
        when(persistence.save(any(ReceivedPix.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReceivedPix pix = ((ReceivePixResult.Received) service.receive(command(null, "100.00", "***.982.247-**", List.of()))).pix();

        assertThat(pix.payer().document()).isNull();
        assertThat(pix.payer().name()).isEqualTo("Pagador");
    }

    @Test
    @DisplayName("Composição do valor divergente não rejeita: o crédito segue e o Pix é gravado")
    void shouldStillCreditThePixWhenComponentsDoNotAddUpToTheAmount() {
        firstDelivery();
        knownReceiver();
        when(persistence.save(any(ReceivedPix.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var divergent = List.of(new ValueComponent(ComponentType.ORIGINAL, Money.of("90.00"), null, null));

        ReceivedPix pix = ((ReceivePixResult.Received) service.receive(command(null, "100.00", "52998224725", divergent))).pix();

        assertThat(pix.componentsMatchAmount()).isFalse();
        assertThat(pix.amount()).isEqualByComparingTo(Money.of("100.00"));
        assertThat(pix.refundableAmount()).as("devolvível nasce igual ao valor do Pix").isEqualByComparingTo(Money.of("100.00"));
    }

    // ------------------------------------------------------------------ helpers

    private void firstDelivery() {
        when(inbox.firstTime("grp", "msg-1", "pix.spi.received.v1", 0, 7L)).thenReturn(true);
        when(persistence.findByEndToEndId(E2E)).thenReturn(Optional.empty());
    }

    private void knownReceiver() {
        when(accounts.findByIdentity("12345678", "0001", "99", "CACC")).thenReturn(Optional.of(
                new AccountInfo(receiverId, SequentialIds.id(20), HolderType.INDIVIDUAL, Cpf.of("11144477735"), "hash", "Recebedor",
                        "12345678", "0001", "99", "CACC", AccountStatus.ACTIVE)));
    }

    private ReceivePixCommand command(String txid, String amount, String payerDocument, List<ValueComponent> components) {
        return new ReceivePixCommand("grp", "msg-1", "pix.spi.received.v1", 0, 7L, E2E, txid, Money.of(amount), SETTLED, "12345678",
                "0001", "99", "CACC", payerDocument, "Pagador", "87654321", "0002", "123", "CACC", null, components);
    }

    private ReceivedPix storedPix() {
        return ReceivedPix.receive(SequentialIds.id(1), new EndToEndId(E2E), TXID, receiverId, Money.of("100.00"),
                new PixPayer(null, "Pagador", "87654321", "0002", "123", "CACC"), null, SETTLED, "msg-0", List.of(), SETTLED);
    }

    private PixReceivedEvent publishedEvent() {
        var captor = ArgumentCaptor.forClass(PixReceivedEvent.class);
        verify(events).publish(captor.capture());
        return captor.getValue();
    }
}
