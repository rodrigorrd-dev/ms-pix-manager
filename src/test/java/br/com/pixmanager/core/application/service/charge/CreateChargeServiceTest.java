package br.com.pixmanager.core.application.service.charge;

import br.com.pixmanager.core.domain.entity.Caller;
import br.com.pixmanager.core.domain.entity.Charge;
import br.com.pixmanager.core.domain.entity.Debtor;
import br.com.pixmanager.core.domain.entity.Money;
import br.com.pixmanager.core.domain.entity.PixLocation;
import br.com.pixmanager.core.domain.entity.TxId;
import br.com.pixmanager.core.domain.entity.event.ChargeEvent;
import br.com.pixmanager.core.domain.enums.ChargeStatus;
import br.com.pixmanager.core.domain.enums.ChargeType;
import br.com.pixmanager.core.port.in.charge.CreateChargeUseCase.CreateChargeCommand;
import br.com.pixmanager.core.port.in.charge.CreateChargeUseCase.CreateChargeResult;
import br.com.pixmanager.core.port.out.directory.KeyDirectoryPortOut;
import br.com.pixmanager.core.port.out.directory.KeyDirectoryPortOut.ActiveKey;
import br.com.pixmanager.core.port.out.event.DomainEventPublisherPortOut;
import br.com.pixmanager.core.port.out.persistence.ChargePersistencePortOut;
import br.com.pixmanager.core.port.out.persistence.LocationPortOut;
import br.com.pixmanager.shared.constant.ErrorCodes;
import br.com.pixmanager.shared.exception.BadRequestException;
import br.com.pixmanager.shared.exception.BusinessException;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreateChargeService — chave ativa do recebedor, txid único por documento (idempotente) e evento na mesma TX")
class CreateChargeServiceTest {

    static final String RAW_KEY = "52998224725";
    static final String TXID = "cobranca0000000000000000000001";
    static final String DOC_HASH = "doc-hash";
    static final int DEFAULT_EXPIRATION = 86_400;

    @Mock ChargePersistencePortOut persistence;
    @Mock KeyDirectoryPortOut keys;
    @Mock LocationPortOut locations;
    @Mock DomainEventPublisherPortOut events;

    final UUID accountId = SequentialIds.id(10);
    final ActiveKey activeKey = new ActiveKey(SequentialIds.id(30), accountId, DOC_HASH);
    final Caller caller = Caller.forAccounts("client", accountId);
    final PixLocation location = new PixLocation(SequentialIds.id(40), 123L, ChargeType.IMMEDIATE, "pix.example/qr/abc");
    CreateChargeService service;

    @BeforeEach
    void setUp() {
        service = new CreateChargeService(persistence, keys, locations, events, new SequentialIds(),
                FixedClock.at("2026-10-15T15:00:00Z"), DEFAULT_EXPIRATION);
    }

    @Test
    void shouldCreateImmediateChargeWithDefaultExpirationLocationAndEvent() {
        when(keys.findActiveKey(RAW_KEY)).thenReturn(Optional.of(activeKey));
        when(persistence.findByReceiverHashAndTxid(DOC_HASH, new TxId(TXID))).thenReturn(Optional.empty());
        when(locations.create(ChargeType.IMMEDIATE)).thenReturn(location);
        when(persistence.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateChargeResult result = service.create(caller, immediate(TXID, "50.00", null));

        assertThat(result).isInstanceOf(CreateChargeResult.Created.class);
        Charge charge = ((CreateChargeResult.Created) result).details().charge();
        assertThat(charge.status()).isEqualTo(ChargeStatus.ATIVA);
        assertThat(charge.expirationSeconds()).isEqualTo(DEFAULT_EXPIRATION);
        assertThat(charge.expiresAt()).isEqualTo(Instant.parse("2026-10-16T15:00:00Z"));
        assertThat(charge.location()).isEqualTo(location);
        assertThat(charge.receiverAccountId()).isEqualTo(accountId);
        var event = ArgumentCaptor.forClass(ChargeEvent.class);
        verify(events).publish(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("ChargeCreated");
    }

    @Test
    void shouldHonorTheExpirationInformedByTheCaller() {
        when(keys.findActiveKey(RAW_KEY)).thenReturn(Optional.of(activeKey));
        when(persistence.findByReceiverHashAndTxid(any(), any())).thenReturn(Optional.empty());
        when(locations.create(ChargeType.IMMEDIATE)).thenReturn(location);
        when(persistence.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Charge charge = ((CreateChargeResult.Created) service.create(caller, immediate(TXID, "50.00", 3600))).details().charge();

        assertThat(charge.expiresAt()).isEqualTo(Instant.parse("2026-10-15T16:00:00Z"));
    }

    @Test
    @DisplayName("POST /cob sem txid: o PSP gera um txid válido (32 hex)")
    void shouldGenerateTheTxidWhenTheCallerOmitsIt() {
        when(keys.findActiveKey(RAW_KEY)).thenReturn(Optional.of(activeKey));
        when(persistence.findByReceiverHashAndTxid(any(), any())).thenReturn(Optional.empty());
        when(locations.create(ChargeType.IMMEDIATE)).thenReturn(location);
        when(persistence.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Charge charge = ((CreateChargeResult.Created) service.create(caller, immediate(null, "50.00", null))).details().charge();

        assertThat(charge.txid().value()).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void shouldCreateDueChargeWithDefaultValidityAfterDue() {
        when(keys.findActiveKey(RAW_KEY)).thenReturn(Optional.of(activeKey));
        when(persistence.findByReceiverHashAndTxid(any(), any())).thenReturn(Optional.empty());
        when(locations.create(ChargeType.DUE)).thenReturn(location);
        when(persistence.save(any(Charge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var command = new CreateChargeCommand(ChargeType.DUE, TXID, RAW_KEY, Money.of("200.00"), null, LocalDate.of(2026, 11, 1), null,
                new Debtor(Cpf.of("11144477735"), "Devedor"), null, List.of());

        Charge charge = ((CreateChargeResult.Created) service.create(caller, command)).details().charge();

        assertThat(charge.type()).isEqualTo(ChargeType.DUE);
        assertThat(charge.validityAfterDueDays()).isEqualTo(30);
        assertThat(charge.dueDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("BR-COB-01: mesmo txid e mesmo pedido ⇒ devolve a existente, sem gravar nem publicar")
    void shouldReturnTheExistingChargeForAnIdenticalRequest() {
        when(keys.findActiveKey(RAW_KEY)).thenReturn(Optional.of(activeKey));
        Charge existing = Charge.createImmediate(SequentialIds.id(500), new TxId(TXID), accountId, DOC_HASH, activeKey.keyId(),
                Money.of("50.00"), DEFAULT_EXPIRATION, null, null, List.of(), location, Instant.parse("2026-10-15T15:00:00Z"));
        when(persistence.findByReceiverHashAndTxid(DOC_HASH, new TxId(TXID))).thenReturn(Optional.of(existing));

        CreateChargeResult result = service.create(caller, immediate(TXID, "50.00", null));

        assertThat(result).isInstanceOf(CreateChargeResult.AlreadyExists.class);
        assertThat(((CreateChargeResult.AlreadyExists) result).details().charge()).isSameAs(existing);
        verify(persistence, never()).save(any());
        verifyNoInteractions(locations, events);
    }

    @Test
    @DisplayName("BR-COB-01: mesmo txid com valor diferente ⇒ TxIdConflict (valor, não exceção)")
    void shouldReportTxIdConflictForADifferentRequest() {
        when(keys.findActiveKey(RAW_KEY)).thenReturn(Optional.of(activeKey));
        Charge existing = Charge.createImmediate(SequentialIds.id(500), new TxId(TXID), accountId, DOC_HASH, activeKey.keyId(),
                Money.of("50.00"), DEFAULT_EXPIRATION, null, null, List.of(), location, Instant.parse("2026-10-15T15:00:00Z"));
        when(persistence.findByReceiverHashAndTxid(DOC_HASH, new TxId(TXID))).thenReturn(Optional.of(existing));

        CreateChargeResult result = service.create(caller, immediate(TXID, "75.00", null));

        assertThat(result).isInstanceOf(CreateChargeResult.TxIdConflict.class);
        verify(persistence, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void shouldTreatAnUnknownKeyAsNotActive() {
        when(keys.findActiveKey(RAW_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(caller, immediate(TXID, "50.00", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(ErrorCodes.KEY_NOT_ACTIVE));

        verifyNoInteractions(persistence, locations, events);
    }

    @Test
    @DisplayName("Chave de outro recebedor é indistinguível de chave inexistente")
    void shouldTreatAKeyOfAnotherReceiverAsNotActive() {
        Caller other = Caller.forAccounts("other", SequentialIds.id(99));
        when(keys.findActiveKey(RAW_KEY)).thenReturn(Optional.of(activeKey));

        assertThatThrownBy(() -> service.create(other, immediate(TXID, "50.00", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode()).isEqualTo(ErrorCodes.KEY_NOT_ACTIVE));

        verifyNoInteractions(persistence, events);
    }

    @Test
    void shouldRejectADueChargeWithoutDebtor() {
        when(keys.findActiveKey(RAW_KEY)).thenReturn(Optional.of(activeKey));
        var command = new CreateChargeCommand(ChargeType.DUE, TXID, RAW_KEY, Money.of("200.00"), null, LocalDate.of(2026, 11, 1), 10, null,
                null, List.of());

        assertThatThrownBy(() -> service.create(caller, command)).isInstanceOf(BadRequestException.class);

        verify(persistence, never()).save(any());
        verifyNoInteractions(events);
    }

    // ------------------------------------------------------------------ helpers

    private CreateChargeCommand immediate(String txid, String amount, Integer expirationSeconds) {
        return new CreateChargeCommand(ChargeType.IMMEDIATE, txid, RAW_KEY, Money.of(amount), expirationSeconds, null, null, null, null,
                List.of());
    }
}
